param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$UserId = "load-user-1",
    [int]$Requests = 10
)

for ($i = 0; $i -lt $Requests; $i++) {
    $body = @{
        sessionId = "workflow-load-$i"
        userId = $UserId
        userMessage = "帮我做营销发布流程"
        context = @{
            domain = "marketing"
            requireApproval = $false
            nextDomain = "media"
        }
        channel = "load-test"
        stream = $false
    } | ConvertTo-Json -Depth 6

    try {
        $response = Invoke-RestMethod -Method Post `
            -Uri "$BaseUrl/agent/supervisor/workflows/async" `
            -Headers @{ "X-User-Id" = $UserId; "Content-Type" = "application/json" } `
            -Body $body
        Write-Host "accepted[$i] asyncWorkflowId=$($response.data.asyncWorkflowId)"
    } catch {
        Write-Host "failed[$i] $($_.Exception.Message)"
    }
}
