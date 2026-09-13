param(
    [int]$Port = 8080
)

$ErrorActionPreference = "Stop"
$proofRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repositoryRoot = Split-Path -Parent (Split-Path -Parent $proofRoot)
$targetRoot = Join-Path $proofRoot "target"
$webRoot = Join-Path $targetRoot "web"
$levelDirectory = Join-Path $repositoryRoot "src\levels\nivel0"
$localMaven = Join-Path $proofRoot ".tools\apache-maven-3.9.11\bin\mvn.cmd"

if (Get-Command mvn -ErrorAction SilentlyContinue) {
    $maven = "mvn"
} elseif (Test-Path $localMaven) {
    $maven = $localMaven
} else {
    throw "Maven no esta instalado. Ejecuta make install-web-tools."
}

Push-Location $proofRoot
try {
    & $maven package
    New-Item -ItemType Directory -Force -Path $webRoot | Out-Null
    $level = Get-ChildItem -LiteralPath $levelDirectory -Filter "*.txt" -File |
        Sort-Object Name |
        Select-Object -First 1
    if (-not $level) {
        throw "No hay niveles .txt en $levelDirectory. Ejecuta primero el pipeline de niveles."
    }
    Copy-Item (Join-Path $targetRoot "classes\index.html") (Join-Path $webRoot "index.html") -Force
    Copy-Item $level.FullName (Join-Path $webRoot "level.txt") -Force
    Copy-Item (Join-Path $targetRoot "classes\wasm-gc-module-runtime.js") (Join-Path $webRoot "wasm-gc-module-runtime.js") -Force
    Write-Host "Abre http://localhost:$Port/"
    python -m http.server $Port --directory $webRoot
}
finally {
    Pop-Location
}
