param([uri]$Endpoint = 'http://127.0.0.1:18081/mcp')

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$configDir = Join-Path $projectRoot '.codex'
$configPath = Join-Path $configDir 'config.toml'
# Load credentials from environment/.env; never embed them in tracked files.
$headers = & (Join-Path $PSScriptRoot 'mysql-mcp-headers.ps1') | ConvertFrom-Json
if (-not $headers.Authorization) { throw 'MCP authentication headers are missing.' }
$entry = @"
[mcp_servers.bk_mysql_readonly]
url = $(ConvertTo-Json -InputObject $Endpoint.AbsoluteUri -Compress)
http_headers = { Authorization = $(ConvertTo-Json -InputObject $headers.Authorization -Compress) }
enabled = true
startup_timeout_sec = 30
tool_timeout_sec = 15
enabled_tools = ["list_databases", "list_tables", "describe_table", "query"]
"@

New-Item -ItemType Directory -Path $configDir -Force | Out-Null
$content = if (Test-Path -LiteralPath $configPath) { [IO.File]::ReadAllText($configPath) } else { '' }
if ($content) { [IO.File]::WriteAllText($configPath + '.bak', $content) }
$pattern = '(?ms)^\[mcp_servers\.bk_mysql_readonly\][^\r\n]*\r?\n.*?(?=^\[|\z)'
if ([regex]::IsMatch($content, $pattern)) {
    $replacement = $entry.TrimEnd() + "`n`n"
    $content = [regex]::Replace($content, $pattern, [System.Text.RegularExpressions.MatchEvaluator]{ param($match) $replacement })
} else {
    $content = $content.TrimEnd("`r", "`n") + "`n`n" + $entry + "`n"
}
[IO.File]::WriteAllText($configPath, $content.TrimStart("`r", "`n"), (New-Object System.Text.UTF8Encoding($false)))
Write-Host 'Project MCP configured: bk_mysql_readonly'
Write-Host 'Credentials loaded into the ignored local config; no secrets were printed.'
