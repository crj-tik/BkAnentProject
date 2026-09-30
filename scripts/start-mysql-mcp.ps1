param([switch]$PrepareOnly)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $projectRoot '.env'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$content = if (Test-Path -LiteralPath $envPath) { [IO.File]::ReadAllText($envPath) } else { '' }
$changed = $false

foreach ($name in @('MYSQL_MCP_PASSWORD', 'MYSQL_MCP_TOKEN')) {
    # Preserve credentials supplied either by the shell or an existing .env.
    if (-not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) { continue }
    $pattern = '(?m)^' + $name + '=(.*)$'
    $match = [regex]::Match($content, $pattern)
    if ($match.Success -and $match.Groups[1].Value.Trim() -notin @('', '""', "''")) { continue }

    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $secret = [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
    $line = $name + '=' + $secret
    if ($match.Success) {
        $content = [regex]::Replace($content, $pattern, $line)
    } else {
        $content = $content.TrimEnd("`r", "`n") + "`n" + $line + "`n"
    }
    $changed = $true
}

if ($changed) {
    [IO.File]::WriteAllText($envPath, $content.TrimStart("`r", "`n"), $utf8)
    Write-Host 'MCP credentials prepared in the ignored .env file.'
} else {
    Write-Host 'Existing MCP credentials preserved.'
}
if ($PrepareOnly) { return }

Push-Location $projectRoot
try {
    docker compose --profile mcp up -d --build --wait mysql-mcp
    if ($LASTEXITCODE -ne 0) { throw 'MySQL MCP startup failed. Check Docker and service logs.' }
    Write-Host 'MySQL MCP is ready. Default endpoint: http://127.0.0.1:18081/mcp'
} finally {
    Pop-Location
}
