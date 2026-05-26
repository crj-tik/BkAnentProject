param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$UserId = "load-user-1",
    [int]$Requests = 20,
    [int]$Concurrency = 5
)

$body = @{
    sessionId = "load-session-1"
    userId = $UserId
    userMessage = "帮我生成营销文案并给出发布建议"
    context = @{
        domain = "marketing"
        requireApproval = $false
    }
    channel = "load-test"
    stream = $false
} | ConvertTo-Json -Depth 6

$jobs = @()
for ($i = 0; $i -lt $Requests; $i++) {
    while (($jobs | Where-Object { $_.State -eq "Running" }).Count -ge $Concurrency) {
        Start-Sleep -Milliseconds 200
    }
    $jobs += Start-Job -ScriptBlock {
        param($BaseUrl, $UserId, $Body, $Index)
        try {
            Invoke-RestMethod -Method Post `
                -Uri "$BaseUrl/agent/supervisor/tasks" `
                -Headers @{ "X-User-Id" = $UserId; "Content-Type" = "application/json" } `
                -Body $Body | Out-Null
            [pscustomobject]@{ index = $Index; status = "OK" }
        } catch {
            [pscustomobject]@{ index = $Index; status = "FAILED"; error = $_.Exception.Message }
        }
    } -ArgumentList $BaseUrl, $UserId, $body, $i
}

$results = $jobs | Receive-Job -Wait -AutoRemoveJob
$success = ($results | Where-Object { $_.status -eq "OK" }).Count
$failed = ($results | Where-Object { $_.status -ne "OK" }).Count

Write-Host "requests=$Requests success=$success failed=$failed"
$results | Format-Table -AutoSize
