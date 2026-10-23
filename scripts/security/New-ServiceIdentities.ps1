$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$keygen = Join-Path $PSScriptRoot 'ServiceIdentityKeygen.java'
Push-Location $repoRoot
try {
    & java $keygen @args
    if ($LASTEXITCODE -ne 0) { throw "Java identity generator failed with exit code $LASTEXITCODE" }

    $identityRoot = Join-Path $repoRoot '.secrets\service-identities'
    $currentSid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $privateFiles = Get-ChildItem -LiteralPath $identityRoot -Filter 'private-key.pem' -File -Recurse
    foreach ($privateFile in $privateFiles) {
        & icacls $privateFile.FullName /inheritance:r /grant:r "*$($currentSid):(R)" '*S-1-5-18:(R)' '*S-1-5-32-544:(R)' | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Could not restrict ACL for a private identity file" }
    }
    Write-Output 'Service identities are ready. Private keys are restricted to this Windows user, SYSTEM, and Administrators.'
}
finally {
    Pop-Location
}
