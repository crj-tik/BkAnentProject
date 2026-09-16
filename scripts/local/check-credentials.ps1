param(
    [string]$Root = (Get-Location).Path
)

$extensions = @("*.yml", "*.yaml", "*.properties", "*.env", ".env")
$files = Get-ChildItem -LiteralPath $Root -Recurse -File -Include $extensions |
    Where-Object { $_.FullName -notmatch "[\\/]target[\\/]" -and $_.FullName -notmatch "[\\/]\.git[\\/]" }

$violations = New-Object System.Collections.Generic.List[string]
$keyPattern = '(?i)^\s*(api-key|access-key|secret-key|password|token|secret)\s*:\s*(.+?)\s*(#.*)?$'
$assignmentPattern = '(?i)^\s*(api[_-]?key|access[_-]?key|secret[_-]?key|password|token|secret)\s*=\s*(.+?)\s*$'
$secretPattern = '(?i)(sk-[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{20,})'

foreach ($file in $files) {
    $lineNumber = 0
    foreach ($line in Get-Content -LiteralPath $file.FullName) {
        $lineNumber++
        $value = $null
        if ($line -match $keyPattern) {
            $value = $Matches[2].Trim().Trim('"', "'")
        } elseif ($line -match $assignmentPattern) {
            $value = $Matches[2].Trim().Trim('"', "'")
        }

        if ($value -and $value -notmatch '^\$\{.*\}$' -and $value -notmatch '^(?i)null|none|changeme|replace[-_ ]?me|your[-_ ]') {
            $violations.Add("$($file.FullName):$lineNumber contains a non-placeholder credential value")
        }
        if ($line -match $secretPattern) {
            $violations.Add("$($file.FullName):$lineNumber contains a recognizable credential pattern")
        }
    }
}

if ($violations.Count -gt 0) {
    $violations | ForEach-Object { Write-Error $_ }
    exit 1
}

Write-Host "Credential safety scan passed: no usable credential values found in checked-in configuration."
