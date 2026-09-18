param(
    [int]$Port = 8080
)

$ErrorActionPreference = "Stop"
$proofRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repositoryRoot = Split-Path -Parent (Split-Path -Parent $proofRoot)
$targetRoot = Join-Path $proofRoot "target"
$webRoot = Join-Path $targetRoot "web"
$levelDirectory = Join-Path $repositoryRoot "src\levels\nivel0"
# Maven se instala globalmente en el perfil del usuario (ver install-tools.ps1).
# Este fallback cubre el caso de una terminal que aun no recargo el PATH tras
# la instalacion.
$globalMavenBin = Join-Path $env:LOCALAPPDATA "MarioAiTools\apache-maven-3.9.11\bin"
$globalMaven = Join-Path $globalMavenBin "mvn.cmd"

if (Get-Command mvn -ErrorAction SilentlyContinue) {
    $maven = "mvn"
} elseif (Test-Path $globalMaven) {
    $maven = $globalMaven
} else {
    throw "Maven no esta instalado. Ejecuta make install-web-tools y abre una terminal nueva si es la primera instalacion."
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
