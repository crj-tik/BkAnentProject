param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$UserId = "load-user-1",
    [int]$Requests = 10,
    [int]$Concurrency = 3
)

$jobs = @()
for ($i = 0; $i -lt $Requests; $i++) {
    while (($jobs | Where-Object { $_.State -eq "Running" }).Count -ge $Concurrency) {
        Start-Sleep -Milliseconds 200
    }

    $body = @{
        sessionId = "parallel-load-$i"
        userId = $UserId
        userMessage = "帮我并行分析房源和交易风险"
        context = @{
            parallelDomains = @("listing", "trade")
            autoRouteAfterParallel = $true
            requireApproval = $false
        }
        channel = "load-test"
        stream = $false
    } | ConvertTo-Json -Depth 6

    $jobs += Start-Job -ScriptBlock {
        param($BaseUrl, $UserId, $Body, $Index)
        try {
            $response = Invoke-RestMethod -Method Post `
                -Uri "$BaseUrl/agent/supervisor/workflows" `
                -Headers @{ "X-User-Id" = $UserId; "Content-Type" = "application/json" } `
                -Body $Body
            [pscustomobject]@{
                index = $Index
                status = "OK"
                taskId = $response.data.taskId
                workflowStatus = $response.data.status
                selectedAgentId = $response.data.selectedAgentId
            }
        } catch {
            [pscustomobject]@{
                index = $Index
                status = "FAILED"
                error = $_.Exception.Message
            }
        }
    } -ArgumentList $BaseUrl, $UserId, $body, $i
}

$results = $jobs | Receive-Job -Wait -AutoRemoveJob
$success = ($results | Where-Object { $_.status -eq "OK" }).Count
$failed = ($results | Where-Object { $_.status -ne "OK" }).Count

Write-Host "requests=$Requests success=$success failed=$failed"
$results | Format-Table -AutoSize
