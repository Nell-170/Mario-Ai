param(
    [string]$Framework = "../mario_ai_framework"
)

$ErrorActionPreference = "Stop"
$bin = Join-Path $Framework "bin"
$src = Join-Path $Framework "src"

Write-Output "[ 25%] [█████░░░░░░░░░░░░░░░] Aplicando parches al framework..."
& (Join-Path $PSScriptRoot "apply-framework-patch.ps1") -Framework $Framework
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Output "[ 50%] [██████████░░░░░░░░░░] Instalando herramientas Java personalizadas..."
New-Item -ItemType Directory -Force -Path $bin | Out-Null
Copy-Item "tools\ValidateLevels.java" (Join-Path $src "ValidateLevels.java") -Force
Copy-Item "tools\PlayHuman.java" (Join-Path $src "PlayHuman.java") -Force
Copy-Item "tools\LevelSelector.java" (Join-Path $src "LevelSelector.java") -Force
Copy-Item "tools\ExperimentRunner.java" (Join-Path $src "ExperimentRunner.java") -Force

Write-Output "[ 75%] [███████████████░░░░░] Compilando archivos Java con javac..."
$javaFiles = Get-ChildItem -Path $src -Filter *.java -Recurse |
    Select-Object -ExpandProperty FullName

if ($javaFiles.Count -eq 0) {
    throw "No Java source files found in $src"
}

& javac --release 25 -d $bin -encoding UTF-8 $javaFiles
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Output "[100%] [████████████████████] ¡Compilación completada exitosamente!"
