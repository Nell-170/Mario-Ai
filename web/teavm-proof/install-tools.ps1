$ErrorActionPreference = "Stop"

if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
    throw "winget no esta disponible. Instala App Installer desde Microsoft Store o instala JDK 17 y Maven manualmente."
}

function Test-Java17 {
    param([string]$JavaPath = "java")
    if ($JavaPath -eq "java" -and -not (Get-Command java -ErrorAction SilentlyContinue)) {
        return $false
    }
    $version = (cmd /c "`"$JavaPath`" -version 2>&1" | Out-String)
    return $version -match 'version "17([.]|")'
}

function Use-InstalledJava17 {
    $candidates = @()
    if ($env:JAVA_HOME) {
        $candidates += Join-Path $env:JAVA_HOME "bin\java.exe"
    }
    $candidates += Get-ChildItem "C:\Program Files\Eclipse Adoptium\jdk-17*\bin\java.exe" -ErrorAction SilentlyContinue
    $candidates += Get-ChildItem "C:\Program Files\Java\jdk-17*\bin\java.exe" -ErrorAction SilentlyContinue
    foreach ($candidate in $candidates) {
        $path = if ($candidate -is [string]) { $candidate } else { $candidate.FullName }
        if ((Test-Path $path) -and (Test-Java17 -JavaPath $path)) {
            $javaHome = Split-Path (Split-Path $path -Parent) -Parent
            $env:JAVA_HOME = $javaHome
            $env:Path = (Join-Path $javaHome "bin") + ";" + $env:Path
            return $true
        }
    }
    return $false
}

function Test-WingetJava17Installed {
    winget list --id "EclipseAdoptium.Temurin.17.JDK" --exact --accept-source-agreements 2>&1 | Out-Null
    return $LASTEXITCODE -eq 0
}

if (Test-Java17) {
    Write-Host "JDK 17 ya esta instalado."
} elseif (Use-InstalledJava17) {
    Write-Host "JDK 17 ya esta instalado y se selecciono para esta terminal."
} else {
    if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
        throw "winget no esta disponible y no se encontro JDK 17. Instala JDK 17 manualmente."
    }
    Write-Host "Instalando JDK 17..."
    winget install --id "EclipseAdoptium.Temurin.17.JDK" --exact --accept-source-agreements --accept-package-agreements
    if ($LASTEXITCODE -ne 0 -and -not (Test-WingetJava17Installed)) {
        throw "No se pudo instalar JDK 17 con winget."
    }
    if (-not (Use-InstalledJava17) -and -not (Test-Java17)) {
        Write-Warning "JDK 17 se instalo, pero esta terminal no tiene actualizada la ruta de Java."
        Write-Host "Cierra y vuelve a abrir PowerShell; luego ejecuta make install-web-tools otra vez."
        exit 0
    }
}

# Maven se instala de forma global (fuera del repositorio) para que no
# dependa del working tree ni se borre con `git clean` u OneDrive: queda en
# el perfil del usuario y se agrega al PATH de usuario de forma persistente.
$mavenVersion   = "3.9.11"
$toolsRoot      = Join-Path $env:LOCALAPPDATA "MarioAiTools"
$mavenRoot      = Join-Path $toolsRoot "apache-maven-$mavenVersion"
$mavenBin       = Join-Path $mavenRoot "bin"
$mavenExecutable = Join-Path $mavenBin "mvn.cmd"

function Add-MavenToUserPath {
    param([string]$BinPath)
    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    $entries = @()
    if ($userPath) { $entries = $userPath -split ";" }
    if ($entries -notcontains $BinPath) {
        $newUserPath = if ($userPath) { "$userPath;$BinPath" } else { $BinPath }
        [Environment]::SetEnvironmentVariable("Path", $newUserPath, "User")
        Write-Host "Se agrego Maven al PATH del usuario. Abre una terminal nueva para que las nuevas sesiones lo vean."
    }
    if ($env:Path -notlike "*$BinPath*") {
        $env:Path = "$BinPath;$env:Path"
    }
}

if (Get-Command mvn -ErrorAction SilentlyContinue) {
    Write-Host "Maven ya esta instalado globalmente."
} elseif (Test-Path $mavenExecutable) {
    Write-Host "Maven global ya esta instalado en $mavenRoot."
    Add-MavenToUserPath -BinPath $mavenBin
} else {
    Write-Host "Descargando Maven $mavenVersion..."
    $archive = Join-Path $env:TEMP "apache-maven-$mavenVersion-bin.zip"
    $downloadUrl = "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$mavenVersion/apache-maven-$mavenVersion-bin.zip"
    New-Item -ItemType Directory -Force -Path $toolsRoot | Out-Null
    Invoke-WebRequest -Uri $downloadUrl -OutFile $archive
    Expand-Archive -LiteralPath $archive -DestinationPath $toolsRoot -Force
    Remove-Item -LiteralPath $archive -Force
    if (-not (Test-Path $mavenExecutable)) {
        throw "No se pudo instalar Maven globalmente."
    }
    Add-MavenToUserPath -BinPath $mavenBin
    Write-Host "Maven instalado globalmente en $mavenRoot."
}

Write-Host "Herramientas listas. Abre una terminal nueva si instalaste JDK 17 o Maven por primera vez."
