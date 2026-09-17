param(
    [string]$Framework = "../mario_ai_framework"
)

$ErrorActionPreference = "Stop"
$bin = Join-Path $Framework "bin"
$src = Join-Path $Framework "src"

Write-Output "Installing custom tools..."
New-Item -ItemType Directory -Force -Path $bin | Out-Null
Copy-Item "tools\ValidateLevels.java" (Join-Path $src "ValidateLevels.java") -Force
Copy-Item "tools\PlayHuman.java" (Join-Path $src "PlayHuman.java") -Force
Copy-Item "tools\LevelSelector.java" (Join-Path $src "LevelSelector.java") -Force

Write-Output "Compiling framework and tools in $src..."
$javaFiles = Get-ChildItem -Path $src -Filter *.java -Recurse |
    Select-Object -ExpandProperty FullName

if ($javaFiles.Count -eq 0) {
    throw "No Java source files found in $src"
}

& javac -d $bin -encoding UTF-8 $javaFiles
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Output "Compilation complete."
