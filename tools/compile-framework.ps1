param(
    [string]$Framework = "mario_ai_framework"
)

$ErrorActionPreference = "Stop"
$bin = Join-Path $Framework "bin"
$src = Join-Path $Framework "src"

New-Item -ItemType Directory -Force -Path $bin | Out-Null
$javaFiles = Get-ChildItem -Path $src -Filter *.java -Recurse |
    Select-Object -ExpandProperty FullName

if ($javaFiles.Count -eq 0) {
    throw "No Java source files found in $src"
}

& javac -d $bin -encoding UTF-8 $javaFiles
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Copy-Item "tools\ValidateLevels.java" (Join-Path $src "ValidateLevels.java") -Force
Copy-Item "tools\PlayHuman.java" (Join-Path $src "PlayHuman.java") -Force
& javac -cp $bin -d $bin `
    (Join-Path $src "ValidateLevels.java") `
    (Join-Path $src "PlayHuman.java")
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
