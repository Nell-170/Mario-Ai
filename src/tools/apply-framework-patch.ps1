param(
    [string]$Framework = "../mario_ai_framework"
)

$ErrorActionPreference = "Stop"
$patch = Join-Path $PSScriptRoot "framework.patch"

Push-Location $Framework
try {
    $ErrorActionPreference = "Continue"
    & git apply --unidiff-zero --ignore-whitespace --reverse --check $patch 2>$null
    $reverseExit = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($reverseExit -eq 0) {
        Write-Output "Framework patch already applied."
    } else {
        & git apply --unidiff-zero --ignore-whitespace $patch
        if ($LASTEXITCODE -ne 0) {
            throw "Could not apply tools/framework.patch to $Framework"
        }
        Write-Output "Framework patch applied."
    }
} finally {
    Pop-Location
}
