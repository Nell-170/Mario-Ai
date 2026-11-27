param(
    [string]$Framework = ""
)

$ErrorActionPreference = "Stop"
$scriptDir = $PSScriptRoot
$rootDir = (Resolve-Path (Join-Path $scriptDir "..\..")).Path

if (-not $Framework) {
    $Framework = Join-Path $rootDir "mario_ai_framework"
} elseif (-not [System.IO.Path]::IsPathRooted($Framework)) {
    $Framework = (Resolve-Path $Framework -ErrorAction SilentlyContinue).Path
    if (-not $Framework) {
        $Framework = Join-Path $rootDir "mario_ai_framework"
    }
}

$patch = Join-Path $scriptDir "framework.patch"
$src = Join-Path $Framework "src"

function Invoke-GitQuiet {
    param([string[]]$Arguments)

    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        & git @Arguments 2>$null
        return $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
}

Push-Location $Framework
try {
    $applied = $false
    # 1. Reverse check
    $reverseCheck = Invoke-GitQuiet -Arguments @("apply", "--reverse", "--check", "--unidiff-zero", "--ignore-whitespace", $patch)
    if ($reverseCheck -eq 0) {
        Write-Output "  [OK] Parches del framework ya estaban aplicados y actualizados."
        $applied = $true
    } else {
        # 2. Forward check
        $forwardCheck = Invoke-GitQuiet -Arguments @("apply", "--check", "--unidiff-zero", "--ignore-whitespace", $patch)
        if ($forwardCheck -eq 0) {
            $applyExitCode = Invoke-GitQuiet -Arguments @("apply", "--unidiff-zero", "--ignore-whitespace", $patch)
            if ($applyExitCode -eq 0) {
                Write-Output "  [OK] Parche del framework aplicado exitosamente."
                $applied = $true
            } else {
                throw "Could not apply tools/framework.patch to $Framework (git exit code $applyExitCode)"
            }
        }
    }

    if (-not $applied) {
        # 3. Conflicto o version vieja del parche: respaldar, restaurar solo los
        #    archivos que toca el parche y volver a aplicarlo (igual que el .sh,
        #    pero guardando primero un respaldo de los cambios locales).
        Write-Output "  [*] El framework tiene cambios previos o en conflicto. Restaurando estado limpio..."
        $patchFiles = @(Get-Content $patch | ForEach-Object {
            if ($_ -match '^diff --git a/(.+) b/') {
                $matches[1]
            }
        })

        $backupDir = Join-Path $Framework ".patch-backups"
        New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
        $backupFile = Join-Path $backupDir ("backup-{0}.diff" -f (Get-Date -Format "yyyyMMdd-HHmmss"))
        $previousPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = "Continue"
            $diffLines = @(& git diff -- $patchFiles 2>$null)
            $diffExitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previousPreference
        }
        if ($diffExitCode -ne 0) {
            throw "Could not back up framework changes in $Framework (git exit code $diffExitCode). No files were reset."
        }
        [System.IO.File]::WriteAllLines($backupFile, [string[]]$diffLines, (New-Object System.Text.UTF8Encoding($false)))
        Write-Output "  [*] Respaldo de cambios locales guardado en: $backupFile"

        $checkoutExitCode = Invoke-GitQuiet -Arguments (@("checkout", "HEAD", "--") + $patchFiles)
        if ($checkoutExitCode -ne 0) {
            throw "Could not restore framework files in $Framework (git exit code $checkoutExitCode). Backup: $backupFile"
        }

        $applyExitCode = Invoke-GitQuiet -Arguments @("apply", "--unidiff-zero", "--ignore-whitespace", $patch)
        if ($applyExitCode -ne 0) {
            throw "Could not apply tools/framework.patch to $Framework after restoring files (git exit code $applyExitCode). Backup: $backupFile"
        }
        Write-Output "  [OK] Parche del framework aplicado exitosamente tras restablecer estado limpio."
    }

    Write-Output "[*] Sincronizando herramientas Java personalizadas..."
    Copy-Item (Join-Path $scriptDir "ValidateLevels.java") (Join-Path $src "ValidateLevels.java") -Force
    Copy-Item (Join-Path $scriptDir "PlayHuman.java") (Join-Path $src "PlayHuman.java") -Force
    Copy-Item (Join-Path $scriptDir "LevelSelector.java") (Join-Path $src "LevelSelector.java") -Force
    Copy-Item (Join-Path $scriptDir "ExperimentRunner.java") (Join-Path $src "ExperimentRunner.java") -Force
    Copy-Item (Join-Path $scriptDir "ExperimentDB.java") (Join-Path $src "ExperimentDB.java") -Force
    New-Item -ItemType Directory -Force -Path (Join-Path $src "engine\audio") | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $src "engine\input") | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $src "engine\helper") | Out-Null
    Copy-Item (Join-Path $scriptDir "engine\audio\ProceduralMusic.java") (Join-Path $src "engine\audio\ProceduralMusic.java") -Force
    Copy-Item (Join-Path $scriptDir "engine\audio\ProceduralEffects.java") (Join-Path $src "engine\audio\ProceduralEffects.java") -Force
    Copy-Item (Join-Path $scriptDir "engine\input\GamepadManager.java") (Join-Path $src "engine\input\GamepadManager.java") -Force
    Copy-Item (Join-Path $scriptDir "engine\helper\LevelTheme.java") (Join-Path $src "engine\helper\LevelTheme.java") -Force
    Write-Output "  [OK] Herramientas Java sincronizadas con exito."
} finally {
    Pop-Location
}
