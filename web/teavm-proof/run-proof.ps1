param(
    [int]$Port = 8080
)

$ErrorActionPreference = "Stop"
$proofRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$targetRoot = Join-Path $proofRoot "target"
$webRoot = Join-Path $targetRoot "web"

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    throw "Maven no esta instalado. Ejecuta mvn package o instala Maven 3.9+."
}

Push-Location $proofRoot
try {
    mvn package
    New-Item -ItemType Directory -Force -Path $webRoot | Out-Null
    Copy-Item (Join-Path $targetRoot "classes\index.html") $webRoot -Force
    Copy-Item (Join-Path $targetRoot "classes\wasm-gc-module-runtime.js") $webRoot -Force
    Write-Host "Abre http://localhost:$Port/"
    python -m http.server $Port --directory $webRoot
}
finally {
    Pop-Location
}
