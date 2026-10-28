[CmdletBinding()]
param(
    [string]$BaseUrl,
    [switch]$KeepFixture
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$envPath = Join-Path $repoRoot '.env'
$privateDir = Join-Path $repoRoot '.secrets\acceptance'
$responsePath = Join-Path $privateDir 'interview-postman-response.json'
$postmanEnvironmentPath = Join-Path $privateDir 'postman-interview-environment.json'
$accessToken = $null
$manageTicket = $null
$readTicket = $null
$manageCredentialId = $null
$readCredentialId = $null
$sessionId = $null
$caseId = $null
$actorId = $null
$stamp = $null
$fixtureCleaned = $false
$loggedOut = $false
$totalAssertions = 0

function Read-DotEnv([string]$Path) {
    $values = @{}
    foreach ($line in [System.IO.File]::ReadAllLines($Path)) {
        if ($line -match '^\s*([^#=\s]+)=(.*)$') {
            $values[$matches[1]] = $matches[2].Trim().Trim('"').Trim("'")
        }
    }
    return $values
}

function Set-PrivateFileAcl([string]$Path) {
    $sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    & icacls $Path /inheritance:r /grant:r "*$($sid):(F)" '*S-1-5-18:(R)' '*S-1-5-32-544:(R)' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not restrict a temporary acceptance file.' }
}

function Write-PostmanEnvironment {
    $document = [ordered]@{
        id = [guid]::NewGuid().ToString()
        name = 'local-interview-authorization-acceptance'
        values = @($script:environmentValues.GetEnumerator() | ForEach-Object {
            @{ key = $_.Key; value = $_.Value; type = 'secret'; enabled = $true }
        })
        _postman_variable_scope = 'environment'
        _postman_exported_using = 'Postman CLI'
    } | ConvertTo-Json -Depth 5
    [System.IO.File]::WriteAllText($postmanEnvironmentPath, $document, [System.Text.UTF8Encoding]::new($false))
    Set-PrivateFileAcl $postmanEnvironmentPath
}

function Invoke-PostmanRequest(
    [string]$Method,
    [string]$Url,
    [string[]]$Headers,
    [string]$Body,
    [string]$Assertion
) {
    if ([System.IO.File]::Exists($responsePath)) { [System.IO.File]::Delete($responsePath) }
    $arguments = @('request', $Method, $Url, '--output', $responsePath)
    foreach ($header in $Headers) { $arguments += @('--header', $header) }
    $arguments += @('--environment', $postmanEnvironmentPath)
    if ($Assertion) { $arguments += @('--script-post-request', ($Assertion -replace "`r|`n", ' ')) }
    if ($null -ne $Body) { $arguments += @('--body', $Body) }
    $null = & postman @arguments 2>&1
    $exitCode = $LASTEXITCODE
    if (-not [System.IO.File]::Exists($responsePath)) {
        throw "Postman did not save the response (exit $exitCode)."
    }
    Set-PrivateFileAcl $responsePath
    $document = Get-Content -Raw $responsePath | ConvertFrom-Json
    $bodyText = [string]$document.response.body
    $bodyJson = if ($bodyText) { $bodyText | ConvertFrom-Json } else { $null }
    $tests = $document.tests[0]
    return [pscustomobject]@{
        Status = [int]$document.response.code
        Body = $bodyJson
        ExitCode = $exitCode
        Passed = [int]$tests.passed
        Failed = [int]$tests.failed
        Total = [int]$tests.total
        FailedTests = (@($tests.results | Where-Object { -not $_.passed } | ForEach-Object { $_.name }) -join ',')
    }
}

function Assert-PostmanResult($Result, [int]$Status, [string]$Label) {
    if ($Result.Status -ne $Status -or $Result.Failed -ne 0 -or $Result.Total -lt 1) {
        throw "$Label failed: HTTP $($Result.Status), Postman assertions $($Result.Passed)/$($Result.Total), failed tests '$($Result.FailedTests)'."
    }
    if ($Status -lt 400 -and $Result.ExitCode -ne 0) {
        throw "$Label returned an unexpected Postman CLI exit code $($Result.ExitCode)."
    }
    $script:totalAssertions += $Result.Total
}

function New-HttpRequest([string]$Method, [string]$Url, [string]$Token, [string]$Ticket) {
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), $Url)
    $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $Token)
    if ($Ticket) { $request.Headers.Add('x-ticket', $Ticket) }
    return $request
}

function Remove-SyntheticFixture {
    if ($KeepFixture -or -not $caseId -or -not $sessionId -or -not $actorId) { return }
    $sql = @"
START TRANSACTION;
DELETE FROM interview_participant_ticket WHERE session_id=$sessionId AND user_id=$actorId;
DELETE FROM interview_participant WHERE session_id=$sessionId AND user_id=$actorId;
DELETE FROM interview_question WHERE case_id=$caseId;
DELETE FROM interview_prep_card WHERE case_id=$caseId;
DELETE FROM interview_session WHERE id=$sessionId AND case_id=$caseId AND created_by_user_id=$actorId;
DELETE FROM interview_case WHERE id=$caseId AND created_by_user_id=$actorId AND objective LIKE 'Authz API acceptance-$stamp synthetic DRAFT%';
SELECT CONCAT('remaining synthetic rows=',
    (SELECT COUNT(*) FROM interview_case WHERE id=$caseId) +
    (SELECT COUNT(*) FROM interview_session WHERE id=$sessionId) +
    (SELECT COUNT(*) FROM interview_participant_ticket WHERE session_id=$sessionId) +
    (SELECT COUNT(*) FROM interview_participant WHERE session_id=$sessionId) +
    (SELECT COUNT(*) FROM interview_question WHERE case_id=$caseId) +
    (SELECT COUNT(*) FROM interview_prep_card WHERE case_id=$caseId));
COMMIT;
"@
    $cleanupOutput = $sql | & docker compose exec -T mysql sh -c 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; mysql --protocol=socket -uroot --batch --skip-column-names bk_interview'
    $cleanupExitCode = $LASTEXITCODE
    if ($cleanupExitCode -ne 0 -or $cleanupOutput -notcontains 'remaining synthetic rows=0') {
        throw 'Could not verify cleanup of the exact synthetic Interview fixture.'
    }
}

Push-Location $repoRoot
try {
    if (-not [System.IO.File]::Exists($envPath)) { throw 'Root .env is required for acceptance credentials.' }
    $settings = Read-DotEnv $envPath
    $username = $settings['API_ACCEPTANCE_USERNAME']
    $password = $settings['API_ACCEPTANCE_PASSWORD']
    if ([string]::IsNullOrWhiteSpace($username) -or [string]::IsNullOrWhiteSpace($password)) {
        throw 'API_ACCEPTANCE_USERNAME and API_ACCEPTANCE_PASSWORD must be configured in root .env.'
    }
    if (-not $BaseUrl) {
        $bindHost = $settings['HOST_BIND_ADDRESS']
        if (-not $bindHost) { $bindHost = '127.0.0.1' }
        $port = $settings['GATEWAY_EXPOSED_PORT']
        if (-not $port) { $port = '5010' }
        $BaseUrl = "http://${bindHost}:${port}"
    }
    $BaseUrl = $BaseUrl.TrimEnd('/')
    New-Item -ItemType Directory -Path $privateDir -Force | Out-Null
    $script:environmentValues = @{
        acceptance_username = $username
        acceptance_password = $password
    }
    Write-PostmanEnvironment

    $loginAssertion = @'
pm.test('login returns a usable access token', function () {
  const body = pm.response.json();
  pm.expect(pm.response.code).to.eql(200);
  pm.expect(body.success).to.eql(true);
  pm.expect(body.data.accessToken).to.be.a('string').and.not.empty;
});
'@
    $login = Invoke-PostmanRequest 'POST' "$BaseUrl/auth/login" @('Content-Type:application/json') '{"username":"{{acceptance_username}}","password":"{{acceptance_password}}"}' $loginAssertion
    Assert-PostmanResult $login 200 'login'
    $accessToken = [string]$login.Body.data.accessToken
    if ([string]::IsNullOrWhiteSpace($accessToken)) { throw 'Auth login returned no access token.' }
    $script:environmentValues['acceptance_access_token'] = $accessToken
    Write-PostmanEnvironment
    $bearer = 'Authorization:Bearer {{acceptance_access_token}}'

    $listAssertion = @'
pm.test('Interview list accepts the signed authenticated actor', function () {
  const body = pm.response.json();
  pm.expect(pm.response.code).to.eql(200);
  pm.expect(body.success).to.eql(true);
  pm.expect(body.data).to.be.an('array');
});
'@
    $list = Invoke-PostmanRequest 'GET' "$BaseUrl/interviews" @($bearer) $null $listAssertion
    Assert-PostmanResult $list 200 'Interview list'

    $meAssertion = @'
pm.test('the signed Interview actor matches the logged-in test identity', function () {
  const body = pm.response.json();
  pm.expect(pm.response.code).to.eql(200);
  pm.expect(body.success).to.eql(true);
  pm.expect(body.data.userId).to.be.above(0);
  pm.expect(body.data.businessAdmin).to.eql(false);
});
'@
    $me = Invoke-PostmanRequest 'GET' "$BaseUrl/auth/me" @($bearer) $null $meAssertion
    Assert-PostmanResult $me 200 'current test identity'
    $actorId = [long]$me.Body.data.userId
    if ($actorId -le 0) { throw 'Current-user endpoint returned an invalid actor ID.' }

    $stamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
    $objective = "Authz API acceptance-$stamp synthetic DRAFT; no model or external tools"
    $openBody = @{
        scene = 'AGENT_DEAL'
        respondentRole = 'BROKER'
        objective = $objective
        caseStatus = 'active'
        referenceMinutes = 15
    } | ConvertTo-Json -Compress
    $openAssertion = @'
pm.test('authenticated actor opens a synthetic Interview draft', function () {
  const body = pm.response.json();
  pm.expect(pm.response.code).to.eql(200);
  pm.expect(body.success).to.eql(true);
  pm.expect(body.data.caseId).to.be.above(0);
  pm.expect(body.data.sessionId).to.be.above(0);
  pm.expect(body.data.entryPath).to.match(/^\/interviews\/\d+$/);
  pm.expect(body.data.credentialClaimPath).to.match(/^\/interviews\/\d+\/credentials$/);
});
'@
    $opened = Invoke-PostmanRequest 'POST' "$BaseUrl/interviews" @($bearer, 'Content-Type:application/json') $openBody $openAssertion
    Assert-PostmanResult $opened 200 'Interview open'
    $caseId = [long]$opened.Body.data.caseId
    $sessionId = [long]$opened.Body.data.sessionId
    if ($caseId -le 0 -or $sessionId -le 0) { throw 'Interview open returned invalid fixture IDs.' }
    $ownerSql = "SELECT IF((SELECT created_by_user_id FROM interview_case WHERE id=$caseId)=$actorId AND (SELECT created_by_user_id FROM interview_session WHERE id=$sessionId)=$actorId, 'signed actor owns case and session', 'owner mismatch');"
    $ownerOutput = $ownerSql | & docker compose exec -T mysql sh -c 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; mysql --protocol=socket -uroot --batch --skip-column-names bk_interview'
    if ($LASTEXITCODE -ne 0 -or ($ownerOutput -join '').Trim() -ne 'signed actor owns case and session') {
        throw 'Interview case/session owner did not match the actor from the signed /auth/me response.'
    }
    $script:totalAssertions++
    $script:environmentValues['acceptance_session_id'] = [string]$sessionId
    Write-PostmanEnvironment

    $manageTicketAssertion = @'
pm.test('MANAGE ticket is returned once with no-store headers', function () {
  const body = pm.response.json();
  pm.expect(pm.response.code).to.eql(200);
  pm.expect(pm.response.headers.get('cache-control').toLowerCase()).to.include('no-store');
  pm.expect(body.success).to.eql(true);
  pm.expect(body.data.purpose).to.eql('MANAGE');
  pm.expect(body.data.credentialId).to.be.above(0);
  pm.expect(body.data.credential).to.match(/^[A-Za-z0-9_-]{43}$/);
});
'@
    $manage = Invoke-PostmanRequest 'POST' "$BaseUrl/interviews/$sessionId/credentials" @($bearer, 'Content-Type:application/json') '{"purpose":"MANAGE"}' $manageTicketAssertion
    Assert-PostmanResult $manage 200 'MANAGE credential claim'
    $manageTicket = [string]$manage.Body.data.credential
    $manageCredentialId = [long]$manage.Body.data.credentialId
    if ([string]::IsNullOrWhiteSpace($manageTicket) -or $manageCredentialId -le 0) {
        throw 'Interview did not issue a usable MANAGE credential.'
    }
    $script:environmentValues['acceptance_manage_ticket'] = $manageTicket
    Write-PostmanEnvironment
    $manageHeader = 'x-ticket: {{acceptance_manage_ticket}}'

    $questionsAssertion = @'
pm.test('MANAGE ticket authorizes a synthetic question without calling the model', function () {
  const body = pm.response.json();
  pm.expect(pm.response.code).to.eql(200);
  pm.expect(body.success).to.eql(true);
  pm.expect(body.data.candidateCount).to.eql(1);
  pm.expect(body.data.questionIds).to.have.length(1);
});
'@
    $questionsBody = '{"questions":[{"content":"Synthetic acceptance question: describe a recent workflow improvement.","focusLabel":"API acceptance","riskHint":"synthetic only","core":true}]}'
    $questions = Invoke-PostmanRequest 'POST' "$BaseUrl/interviews/$sessionId/questions" @($bearer, $manageHeader, 'Content-Type:application/json') $questionsBody $questionsAssertion
    Assert-PostmanResult $questions 200 'Question creation'
    $questionId = [long]$questions.Body.data.questionIds[0]
    $script:environmentValues['acceptance_question_id'] = [string]$questionId
    Write-PostmanEnvironment

    $confirmAssertion = @'
pm.test('the draft owner confirms only the returned candidate question', function () {
  const body = pm.response.json();
  pm.expect(pm.response.code).to.eql(200);
  pm.expect(body.success).to.eql(true);
  pm.expect(body.data.confirmedCount).to.eql(1);
});
'@
    $confirmBody = '{"questionIds":[{{acceptance_question_id}}]}'
    $confirmed = Invoke-PostmanRequest 'POST' "$BaseUrl/interviews/$sessionId/questions/confirm" @($bearer, $manageHeader, 'Content-Type:application/json') $confirmBody $confirmAssertion
    Assert-PostmanResult $confirmed 200 'Question confirmation'

    $readTicketAssertion = @'
pm.test('READ ticket is returned once with no-store headers', function () {
  const body = pm.response.json();
  pm.expect(pm.response.code).to.eql(200);
  pm.expect(pm.response.headers.get('cache-control').toLowerCase()).to.include('no-store');
  pm.expect(body.success).to.eql(true);
  pm.expect(body.data.purpose).to.eql('READ');
  pm.expect(body.data.credentialId).to.be.above(0);
  pm.expect(body.data.credential).to.match(/^[A-Za-z0-9_-]{43}$/);
});
'@
    $read = Invoke-PostmanRequest 'POST' "$BaseUrl/interviews/$sessionId/credentials" @($bearer, 'Content-Type:application/json') '{"purpose":"READ"}' $readTicketAssertion
    Assert-PostmanResult $read 200 'READ credential claim'
    $readTicket = [string]$read.Body.data.credential
    $readCredentialId = [long]$read.Body.data.credentialId
    if ([string]::IsNullOrWhiteSpace($readTicket) -or $readCredentialId -le 0) {
        throw 'Interview did not issue a usable READ credential.'
    }
    $script:environmentValues['acceptance_read_ticket'] = $readTicket
    Write-PostmanEnvironment
    $readHeader = 'x-ticket: {{acceptance_read_ticket}}'

    $monitorAssertion = @'
pm.test('READ ticket authorizes the initiating actor to monitor their own Interview', function () {
  const body = pm.response.json();
  pm.expect(pm.response.code).to.eql(200);
  pm.expect(body.success).to.eql(true);
  pm.expect(String(body.data.sessionId)).to.eql(pm.environment.get('acceptance_session_id'));
  pm.expect(body.data.status).to.eql('QUESTIONS_CONFIRMED');
});
'@
    $monitor = Invoke-PostmanRequest 'GET' "$BaseUrl/interviews/$sessionId" @($bearer, $readHeader) $null $monitorAssertion
    Assert-PostmanResult $monitor 200 'Interview monitor with READ ticket'

    $noTicketAssertion = @'
pm.test('monitor denies an authenticated actor without a participant ticket', function () {
  const body = pm.response.json();
  pm.expect(pm.response.code).to.eql(200);
  pm.expect(body.success).to.eql(false);
  pm.expect(body.code).to.eql('INTERVIEW_INVALID_CREDENTIAL');
});
'@
    $noTicket = Invoke-PostmanRequest 'GET' "$BaseUrl/interviews/$sessionId" @($bearer) $null $noTicketAssertion
    Assert-PostmanResult $noTicket 200 'Interview monitor without ticket'

    $wrongPurposeAssertion = @'
pm.test('MANAGE ticket cannot be substituted for the READ purpose', function () {
  const body = pm.response.json();
  pm.expect(pm.response.code).to.eql(200);
  pm.expect(body.success).to.eql(false);
  pm.expect(body.code).to.eql('INTERVIEW_INVALID_CREDENTIAL');
});
'@
    $wrongPurpose = Invoke-PostmanRequest 'GET' "$BaseUrl/interviews/$sessionId" @($bearer, $manageHeader) $null $wrongPurposeAssertion
    Assert-PostmanResult $wrongPurpose 200 'Interview ticket purpose separation'

    $queryTicketAssertion = @'
pm.test('participant credentials in query strings are rejected before the controller', function () {
  pm.expect(pm.response.code).to.eql(400);
});
'@
    $queryTicket = Invoke-PostmanRequest 'GET' "$BaseUrl/interviews/$sessionId?ticket=not-a-real-ticket" @($bearer) $null $queryTicketAssertion
    Assert-PostmanResult $queryTicket 400 'Query ticket rejection'

    $turnRequest = New-HttpRequest 'POST' "$BaseUrl/interviews/$sessionId/turns" $accessToken $null
    $turnResponse = $null
    $turnHandler = [System.Net.Http.HttpClientHandler]::new()
    $turnHandler.UseProxy = $false
    $turnRequest.Headers.Add('x-req-id', "acceptance-$stamp")
    $turnRequest.Content = [System.Net.Http.StringContent]::new(
        '{"content":"authz-api-turn-without-respondent-proof","idempotencyKey":"acceptance"}',
        [System.Text.Encoding]::UTF8, 'application/json')
    $turnClient = [System.Net.Http.HttpClient]::new($turnHandler)
    $turnClient.Timeout = [TimeSpan]::FromSeconds(20)
    try {
        $turnResponse = $turnClient.SendAsync($turnRequest).GetAwaiter().GetResult()
        $turnText = $turnResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if ([int]$turnResponse.StatusCode -ne 200 -or
                $turnText -notmatch 'INTERVIEW_AUTHORIZATION_DENIED|INTERVIEW_INVALID_CREDENTIAL' -or
                $turnText.Contains('authz-api-turn-without-respondent-proof')) {
            throw 'Interview rejected-turn boundary did not deny before processing the submitted turn.'
        }
        $script:totalAssertions++
    } finally {
        if ($turnResponse) { $turnResponse.Dispose() }
        $turnRequest.Dispose()
        $turnClient.Dispose()
    }

    $streamRequest = New-HttpRequest 'GET' "$BaseUrl/interviews/$sessionId/stream" $accessToken $readTicket
    $streamRequest.Headers.Accept.Add([System.Net.Http.Headers.MediaTypeWithQualityHeaderValue]::new('text/event-stream'))
    $streamResponse = $null
    $stream = $null
    $streamHandler = [System.Net.Http.HttpClientHandler]::new()
    $streamHandler.UseProxy = $false
    $streamClient = [System.Net.Http.HttpClient]::new($streamHandler)
    $streamClient.Timeout = [TimeSpan]::FromSeconds(22)
    try {
        $streamResponse = $streamClient.SendAsync($streamRequest,
            [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
        if ([int]$streamResponse.StatusCode -ne 200 -or
                -not $streamResponse.Content.Headers.ContentType.MediaType.StartsWith('text/event-stream')) {
            throw 'Authorized Interview SSE stream did not open successfully.'
        }
        $stream = $streamResponse.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
        $buffer = New-Object byte[] 512
        $count = $stream.ReadAsync($buffer, 0, $buffer.Length).GetAwaiter().GetResult()
        $eventText = [System.Text.Encoding]::UTF8.GetString($buffer, 0, $count)
        if ($count -le 0 -or $eventText -notmatch 'event:heartbeat' -or $eventText -notmatch 'data:alive') {
            throw 'Authorized Interview SSE stream did not deliver a heartbeat.'
        }
        $script:totalAssertions++
    } finally {
        if ($stream) { $stream.Dispose() }
        if ($streamResponse) { $streamResponse.Dispose() }
        $streamRequest.Dispose()
        $streamClient.Dispose()
    }

    $revokeAssertion = @'
pm.test('the initiating actor can revoke their own participant credential', function () {
  const body = pm.response.json();
  pm.expect(pm.response.code).to.eql(200);
  pm.expect(body.success).to.eql(true);
  pm.expect(body.data.revoked).to.eql(true);
});
'@
    $revokeRead = Invoke-PostmanRequest 'DELETE' "$BaseUrl/interviews/$sessionId/credentials/$readCredentialId" @($bearer) $null $revokeAssertion
    Assert-PostmanResult $revokeRead 200 'READ credential revocation'

    $revokedTicketAssertion = @'
pm.test('revoked ticket cannot authorize another monitor request', function () {
  const body = pm.response.json();
  pm.expect(pm.response.code).to.eql(200);
  pm.expect(body.success).to.eql(false);
  pm.expect(body.code).to.eql('INTERVIEW_INVALID_CREDENTIAL');
});
'@
    $revokedTicket = Invoke-PostmanRequest 'GET' "$BaseUrl/interviews/$sessionId" @($bearer, $readHeader) $null $revokedTicketAssertion
    Assert-PostmanResult $revokedTicket 200 'Revoked Interview ticket'

    $revokeManage = Invoke-PostmanRequest 'DELETE' "$BaseUrl/interviews/$sessionId/credentials/$manageCredentialId" @($bearer) $null $revokeAssertion
    Assert-PostmanResult $revokeManage 200 'MANAGE credential revocation'

    $logoutAssertion = @'
pm.test('acceptance login is revoked at the end of the run', function () {
  const body = pm.response.json();
  pm.expect(pm.response.code).to.eql(200);
  pm.expect(body.success).to.eql(true);
});
'@
    $logout = Invoke-PostmanRequest 'POST' "$BaseUrl/auth/logout" @('Authorization:Bearer {{acceptance_access_token}}') $null $logoutAssertion
    Assert-PostmanResult $logout 200 'Acceptance logout'
    $loggedOut = $true

    if (-not $KeepFixture) {
        Remove-SyntheticFixture
        $fixtureCleaned = $true
    }

    Write-Output "Interview API acceptance passed: signed list/open, question confirmation, MANAGE/READ ticket isolation, no-store claim, monitor, SSE heartbeat, query-ticket rejection, pre-model turn denial, ticket revocation; $totalAssertions assertions passed."
    if ($KeepFixture) { Write-Output "Synthetic DRAFT fixture retained: caseId=$caseId sessionId=$sessionId." }
}
catch {
    if ($accessToken -and $sessionId) {
        foreach ($credentialId in @($readCredentialId, $manageCredentialId)) {
            if ($credentialId) {
                try {
                    $null = Invoke-PostmanRequest 'DELETE' "$BaseUrl/interviews/$sessionId/credentials/$credentialId" @('Authorization:Bearer {{acceptance_access_token}}') $null $null
                } catch { }
            }
        }
    }
    if ($KeepFixture -and $sessionId) { Write-Warning "The synthetic DRAFT fixture remains for diagnosis: caseId=$caseId sessionId=$sessionId." }
    throw
}
finally {
    if ($accessToken -and -not $loggedOut) {
        try {
            $logoutAssertion = @'
pm.test('acceptance login is revoked at the end of the run', function () {
  const body = pm.response.json();
  pm.expect(pm.response.code).to.eql(200);
  pm.expect(body.success).to.eql(true);
});
'@
            $logout = Invoke-PostmanRequest 'POST' "$BaseUrl/auth/logout" @('Authorization:Bearer {{acceptance_access_token}}') $null $logoutAssertion
            Assert-PostmanResult $logout 200 'Acceptance logout'
        } catch { }
    }
    if (-not $KeepFixture -and -not $fixtureCleaned) {
        try { Remove-SyntheticFixture } catch { Write-Warning $_.Exception.Message }
    }
    foreach ($path in @($responsePath, $postmanEnvironmentPath)) {
        if ([System.IO.File]::Exists($path)) { [System.IO.File]::Delete($path) }
    }
    $username = $null
    $password = $null
    $accessToken = $null
    $manageTicket = $null
    $readTicket = $null
    Pop-Location
}
