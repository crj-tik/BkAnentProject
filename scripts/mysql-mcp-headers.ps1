# Internal credential reader used by setup. Do not print its output in logs.
$ErrorActionPreference = 'Stop'
$token = [Environment]::GetEnvironmentVariable('MYSQL_MCP_TOKEN')
if ([string]::IsNullOrWhiteSpace($token)) {
    $envPath = Join-Path (Split-Path -Parent $PSScriptRoot) '.env'
    if (Test-Path -LiteralPath $envPath) {
        foreach ($line in [IO.File]::ReadAllLines($envPath)) {
            if ($line -match '^\s*MYSQL_MCP_TOKEN\s*=\s*(.*?)\s*$') {
                $token = $Matches[1]
                if ($token.Length -ge 2 -and (
                    ($token.StartsWith('"') -and $token.EndsWith('"')) -or
                    ($token.StartsWith("'") -and $token.EndsWith("'")))) {
                    $token = $token.Substring(1, $token.Length - 2)
                }
            }
        }
    }
}
if ([string]::IsNullOrWhiteSpace($token) -or $token.Length -lt 32 -or $token -match '[\r\n]') {
    throw 'Set a valid MYSQL_MCP_TOKEN in the project .env or process environment.'
}
@{ Authorization = 'Bearer ' + $token } | ConvertTo-Json -Compress
