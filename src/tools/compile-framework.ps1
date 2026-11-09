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
Copy-Item "tools\ExperimentDB.java" (Join-Path $src "ExperimentDB.java") -Force
New-Item -ItemType Directory -Force -Path (Join-Path $src "engine\audio") | Out-Null
Copy-Item "tools\engine\audio\ProceduralMusic.java" (Join-Path $src "engine\audio\ProceduralMusic.java") -Force
Copy-Item "tools\engine\audio\ProceduralEffects.java" (Join-Path $src "engine\audio\ProceduralEffects.java") -Force

Write-Output "[ 75%] [███████████████░░░░░] Compilando archivos Java con javac..."
$javaFiles = Get-ChildItem -Path $src -Filter *.java -Recurse |
    Select-Object -ExpandProperty FullName

if ($javaFiles.Count -eq 0) {
    throw "No Java source files found in $src"
}

try {
    & javac --release 17 -d $bin -encoding UTF-8 $javaFiles
} catch {
    & javac -d $bin -encoding UTF-8 $javaFiles
}
if ($LASTEXITCODE -ne 0) {
    & javac -d $bin -encoding UTF-8 $javaFiles
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}

# Driver JDBC de PostgreSQL (base de datos del experimento): se extrae dentro de bin/
# para que todos los "java -cp bin ..." lo encuentren sin cambiar el classpath.
$libDir = Join-Path $PSScriptRoot "lib"
$binFull = (Resolve-Path $bin).Path
Add-Type -AssemblyName System.IO.Compression.FileSystem
Get-ChildItem -Path $libDir -Filter *.jar -ErrorAction SilentlyContinue | ForEach-Object {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($_.FullName)
    try {
        foreach ($entry in $zip.Entries) {
            if ($entry.FullName.EndsWith("/")) { continue }
            $dest = Join-Path $binFull $entry.FullName
            New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $dest, $true)
        }
    } finally {
        $zip.Dispose()
    }
}

Write-Output "[100%] [████████████████████] ¡Compilación completada exitosamente!"
