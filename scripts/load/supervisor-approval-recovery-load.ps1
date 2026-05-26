param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$UserId = "load-user-1",
    [string]$ReviewerId = "reviewer-1",
    [int]$Requests = 5
)

for ($i = 0; $i -lt $Requests; $i++) {
    $sessionId = "approval-load-$i"
    $body = @{
        sessionId = $sessionId
        userId = $UserId
        userMessage = "帮我生成营销文案，先给我审批"
        context = @{
            domain = "marketing"
            requireApproval = $true
        }
        channel = "load-test"
        stream = $false
    } | ConvertTo-Json -Depth 6

    try {
        $start = Invoke-RestMethod -Method Post `
            -Uri "$BaseUrl/agent/supervisor/workflows" `
            -Headers @{ "X-User-Id" = $UserId; "Content-Type" = "application/json" } `
            -Body $body

        $taskId = $start.data.taskId
        Start-Sleep -Milliseconds 300

        $state = Invoke-RestMethod -Method Get `
            -Uri "$BaseUrl/agent/supervisor/workflows/state?taskId=$taskId&userId=$UserId"

        $approvalId = $state.data.pendingApproval.approvalId
        if (-not $approvalId) {
            Write-Host "task[$i] missing approvalId"
            continue
        }

        $rejectBody = @{
            approvalId = $approvalId
            taskId = $taskId
            sessionId = $sessionId
            status = "REJECTED"
            reviewerId = $ReviewerId
            feedback = "请更偏年轻化一点，并弱化销售语气"
            traceId = $start.data.traceId
        } | ConvertTo-Json -Depth 4

        Invoke-RestMethod -Method Post `
            -Uri "$BaseUrl/agent/supervisor/approvals/callback" `
            -Headers @{ "Content-Type" = "application/json" } `
            -Body $rejectBody | Out-Null

        Start-Sleep -Milliseconds 300
        $stateAfterReject = Invoke-RestMethod -Method Get `
            -Uri "$BaseUrl/agent/supervisor/workflows/state?taskId=$taskId&userId=$UserId"

        $secondApprovalId = $stateAfterReject.data.pendingApproval.approvalId
        $approveBody = @{
            approvalId = $secondApprovalId
            taskId = $taskId
            sessionId = $sessionId
            status = "APPROVED"
            reviewerId = $ReviewerId
            feedback = "通过"
            traceId = $start.data.traceId
        } | ConvertTo-Json -Depth 4

        $approveResult = Invoke-RestMethod -Method Post `
            -Uri "$BaseUrl/agent/supervisor/approvals/callback" `
            -Headers @{ "Content-Type" = "application/json" } `
            -Body $approveBody

        [pscustomobject]@{
            index = $i
            taskId = $taskId
            firstApprovalId = $approvalId
            secondApprovalId = $secondApprovalId
            finalStatus = $approveResult.data.status
        } | Format-Table -AutoSize
    } catch {
        Write-Host "task[$i] failed $($_.Exception.Message)"
    }
}
