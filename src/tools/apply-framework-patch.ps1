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
        $previousPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = "Continue"
            $changedFiles = @(& git diff --name-only -- src/ 2>$null)
            $statusExitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previousPreference
        }
        if ($statusExitCode -ne 0) {
            throw "Could not inspect framework changes in $Framework (git exit code $statusExitCode)"
        }
        $patchFiles = @(Get-Content $patch | ForEach-Object {
            if ($_ -match '^diff --git a/(.+) b/') {
                $matches[1]
            }
        })
        $unmodifiedPatchFiles = @($patchFiles | Where-Object { $_ -notin $changedFiles })
        if ($changedFiles.Count -gt 0 -and $unmodifiedPatchFiles.Count -eq 0) {
            Write-Output "  [*] Los archivos del parche ya tienen cambios locales; se conservan sin restablecerlos."
            $applied = $true
        } else {
            throw "Framework patch conflicts with its current source files. No files were reset or overwritten."
        }
    }

    Write-Output "[*] Sincronizando herramientas Java personalizadas..."
    Copy-Item (Join-Path $scriptDir "ValidateLevels.java") (Join-Path $src "ValidateLevels.java") -Force
    Copy-Item (Join-Path $scriptDir "PlayHuman.java") (Join-Path $src "PlayHuman.java") -Force
    Copy-Item (Join-Path $scriptDir "LevelSelector.java") (Join-Path $src "LevelSelector.java") -Force
    Copy-Item (Join-Path $scriptDir "ExperimentRunner.java") (Join-Path $src "ExperimentRunner.java") -Force
    New-Item -ItemType Directory -Force -Path (Join-Path $src "engine\audio") | Out-Null
    Copy-Item (Join-Path $scriptDir "engine\audio\ProceduralMusic.java") (Join-Path $src "engine\audio\ProceduralMusic.java") -Force
    Copy-Item (Join-Path $scriptDir "engine\audio\ProceduralEffects.java") (Join-Path $src "engine\audio\ProceduralEffects.java") -Force
    Write-Output "  [OK] Herramientas Java sincronizadas con exito."
} finally {
    Pop-Location
}
