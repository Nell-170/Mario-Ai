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
    $alreadyPatched = $false
    if (Test-Path "src\engine\core\MarioGame.java") {
        $gameContent = Get-Content "src\engine\core\MarioGame.java" -Raw
        if ($gameContent -match "ProceduralMusic") {
            $alreadyPatched = $true
        }
    }

    if ($alreadyPatched) {
        Write-Output "  ✓ Parches del framework ya estaban aplicados."
    } else {
        & git apply --unidiff-zero --ignore-whitespace $patch
        if ($LASTEXITCODE -ne 0) {
            throw "Could not apply tools/framework.patch to $Framework"
        }
        Write-Output "  ✓ Parche del framework aplicado exitosamente."
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
