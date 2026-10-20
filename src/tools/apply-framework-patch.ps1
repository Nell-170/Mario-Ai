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

Push-Location $Framework
try {
    $applied = $false
    # 1. Reverse check
    & git apply --reverse --check --unidiff-zero --ignore-whitespace $patch 2>$null
    if ($LASTEXITCODE -eq 0) {
        Write-Output "  ✓ Parches del framework ya estaban aplicados y actualizados."
        $applied = $true
    } else {
        # 2. Forward check
        & git apply --check --unidiff-zero --ignore-whitespace $patch 2>$null
        if ($LASTEXITCODE -eq 0) {
            & git apply --unidiff-zero --ignore-whitespace $patch
            if ($LASTEXITCODE -eq 0) {
                Write-Output "  ✓ Parche del framework aplicado exitosamente."
                $applied = $true
            }
        }
    }

    if (-not $applied) {
        Write-Output "  [*] El framework tiene cambios previos o en conflicto. Restaurando estado limpio..."
        if (Test-Path ".git") {
            & git checkout -f HEAD -- src/ 2>$null
        }
        & git apply --unidiff-zero --ignore-whitespace $patch
        if ($LASTEXITCODE -ne 0) {
            throw "Could not apply tools/framework.patch to $Framework"
        }
        Write-Output "  ✓ Parche del framework aplicado exitosamente tras restablecer estado limpio."
    }

    Write-Output "[*] Sincronizando herramientas Java personalizadas..."
    Copy-Item (Join-Path $scriptDir "ValidateLevels.java") (Join-Path $src "ValidateLevels.java") -Force
    Copy-Item (Join-Path $scriptDir "PlayHuman.java") (Join-Path $src "PlayHuman.java") -Force
    Copy-Item (Join-Path $scriptDir "LevelSelector.java") (Join-Path $src "LevelSelector.java") -Force
    Copy-Item (Join-Path $scriptDir "ExperimentRunner.java") (Join-Path $src "ExperimentRunner.java") -Force
    New-Item -ItemType Directory -Force -Path (Join-Path $src "engine\audio") | Out-Null
    Copy-Item (Join-Path $scriptDir "engine\audio\ProceduralMusic.java") (Join-Path $src "engine\audio\ProceduralMusic.java") -Force
    Copy-Item (Join-Path $scriptDir "engine\audio\ProceduralEffects.java") (Join-Path $src "engine\audio\ProceduralEffects.java") -Force
    Write-Output "  ✓ Herramientas Java sincronizadas con éxito."
} finally {
    Pop-Location
}
