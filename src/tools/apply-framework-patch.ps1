param(
    [string]$Framework = "../mario_ai_framework"
)

$ErrorActionPreference = "Stop"
$patch = Join-Path $PSScriptRoot "framework.patch"
$audioPatch = Join-Path $PSScriptRoot "framework-audio.patch"

Push-Location $Framework
try {
    $ErrorActionPreference = "Continue"
    & git apply --recount --ignore-whitespace --reverse --check $audioPatch 2>$null
    $audioReverseExit = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($audioReverseExit -eq 0) {
        Write-Output "Framework and audio patches already applied."
    } else {
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

        & git apply --recount --ignore-whitespace $audioPatch
        if ($LASTEXITCODE -ne 0) {
            throw "Could not apply tools/framework-audio.patch to $Framework"
        }
        Write-Output "Framework audio patch applied."
    }
} finally {
    Pop-Location
}
