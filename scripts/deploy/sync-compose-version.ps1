$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$envPath = Join-Path $repoRoot '.env'
if (-not (Test-Path -LiteralPath $envPath -PathType Leaf)) {
    throw "Missing .env at $envPath. Copy .env.example and configure secrets first."
}

$revision = (& git -C $repoRoot rev-parse --verify HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $revision -notmatch '^[0-9a-f]{40}$') {
    throw 'Could not resolve the current Git commit SHA.'
}

$lines = [System.IO.File]::ReadAllLines($envPath)
$updated = $false
$result = [System.Collections.Generic.List[string]]::new()
foreach ($line in $lines) {
    if ($line -match '^\s*APP_VERSION=') {
        if (-not $updated) {
            $result.Add("APP_VERSION=$revision")
            $updated = $true
        }
        continue
    }
    $result.Add($line)
}
if (-not $updated) {
    $result.Add("APP_VERSION=$revision")
}

[System.IO.File]::WriteAllLines($envPath, $result, [System.Text.UTF8Encoding]::new($false))
Write-Host "APP_VERSION synchronized to $revision in .env."
