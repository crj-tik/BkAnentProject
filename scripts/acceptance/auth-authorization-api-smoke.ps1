[CmdletBinding()]
param(
    [string]$BaseUrl
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$envPath = Join-Path $repoRoot '.env'
$privateDir = Join-Path $repoRoot '.secrets\acceptance'
$responsePath = Join-Path $privateDir 'postman-response.json'
$postmanEnvironmentPath = Join-Path $privateDir 'postman-auth-environment.json'

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

function Invoke-PostmanRequest(
    [string]$Method,
    [string]$Url,
    [string[]]$Headers,
    [string]$Body,
    [string]$EnvironmentPath,
    [string]$Assertion
) {
    if ([System.IO.File]::Exists($responsePath)) { [System.IO.File]::Delete($responsePath) }
    $arguments = @('request', $Method, $Url, '--output', $responsePath)
    foreach ($header in $Headers) { $arguments += @('--header', $header) }
    if ($EnvironmentPath) { $arguments += @('--environment', $EnvironmentPath) }
    if ($Assertion) { $arguments += @('--script-post-request', $Assertion) }
    if ($null -ne $Body) { $arguments += @('--body', $Body) }
    $cliOutput = & postman @arguments 2>&1
    $exitCode = $LASTEXITCODE
    if (-not [System.IO.File]::Exists($responsePath)) {
        throw "Postman did not save the response (exit $exitCode)."
    }
    Set-PrivateFileAcl $responsePath
    $document = Get-Content -Raw $responsePath | ConvertFrom-Json
    $bodyText = [string]$document.response.body
    $bodyJson = if ($bodyText) { $bodyText | ConvertFrom-Json } else { $null }
    return [pscustomobject]@{
        Status = [int]$document.response.code
        Body = $bodyJson
        ApiCode = [string]$bodyJson.code
        ExitCode = $exitCode
        Passed = [int]$document.tests[0].passed
        Failed = [int]$document.tests[0].failed
        Total = [int]$document.tests[0].total
        FailedTests = (@($document.tests[0].results | Where-Object { -not $_.passed } | ForEach-Object { $_.name }) -join ',')
    }
}

function Assert-PostmanResult($Result, [int]$Status, [string]$Label) {
    if ($Result.Status -ne $Status -or $Result.Failed -ne 0 -or $Result.Total -lt 1) {
        throw "$Label failed: HTTP $($Result.Status), API code '$($Result.ApiCode)', Postman assertions $($Result.Passed)/$($Result.Total), failed tests '$($Result.FailedTests)'."
    }
    if ($Status -lt 400 -and $Result.ExitCode -ne 0) {
        throw "$Label returned an unexpected Postman CLI exit code $($Result.ExitCode)."
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

    $environment = [ordered]@{
        id = [guid]::NewGuid().ToString()
        name = 'local-auth-authorization-acceptance'
        values = @(
            @{ key = 'acceptance_username'; value = $username; type = 'secret'; enabled = $true },
            @{ key = 'acceptance_password'; value = $password; type = 'secret'; enabled = $true }
        )
        _postman_variable_scope = 'environment'
        _postman_exported_using = 'Postman CLI'
    } | ConvertTo-Json -Depth 5
    [System.IO.File]::WriteAllText($postmanEnvironmentPath, $environment, [System.Text.UTF8Encoding]::new($false))
    Set-PrivateFileAcl $postmanEnvironmentPath
    $loginBody = '{"username":"{{acceptance_username}}","password":"{{acceptance_password}}"}'
    $loginAssertion = @'
pm.test('login returns a usable access token', function () {
  const body = pm.response.json();
  pm.expect(pm.response.code).to.eql(200);
  pm.expect(body.success).to.eql(true);
  pm.expect(typeof body.data.accessToken).to.eql('string');
  pm.expect(body.data.accessToken.length).to.be.above(0);
});
'@ -replace "`r|`n", ' '
    $login = Invoke-PostmanRequest 'POST' "$BaseUrl/auth/login" @('Content-Type:application/json') $loginBody $postmanEnvironmentPath $loginAssertion
    Assert-PostmanResult $login 200 'login'
    if ($login.Body.success -ne $true -or -not $login.Body.data.accessToken -or -not $login.Body.data.refreshToken) {
        throw 'Auth login did not return valid acceptance tokens.'
    }
    $accessToken = [string]$login.Body.data.accessToken
    $refreshToken = [string]$login.Body.data.refreshToken
    [System.IO.File]::Delete($responsePath)

    $environmentObject = Get-Content -Raw $postmanEnvironmentPath | ConvertFrom-Json
    $environmentObject.values = @(
        @{ key = 'acceptance_access_token'; value = $accessToken; type = 'secret'; enabled = $true },
        @{ key = 'acceptance_refresh_token'; value = $refreshToken; type = 'secret'; enabled = $true },
        @{ key = 'acceptance_username'; value = $username; type = 'secret'; enabled = $true }
    )
    [System.IO.File]::WriteAllText($postmanEnvironmentPath, ($environmentObject | ConvertTo-Json -Depth 5), [System.Text.UTF8Encoding]::new($false))
    Set-PrivateFileAcl $postmanEnvironmentPath

    $bearerHeader = 'Authorization:Bearer {{acceptance_access_token}}'
    $meAssertion = @'
pm.test('current user response is successful', function () {
  const body = pm.response.json();
  pm.expect(pm.response.code).to.eql(200);
  pm.expect(body.success).to.eql(true);
});
pm.test('current user has BROKER primary role', function () {
  const body = pm.response.json();
  pm.expect(body.data.roleCode).to.eql('BROKER');
});
pm.test('current user is not a business administrator', function () {
  const body = pm.response.json();
  pm.expect(body.data.businessAdmin).to.eql(false);
});
pm.test('current user includes BROKER in effective roles', function () {
  const body = pm.response.json();
  pm.expect(body.data.roles).to.include('BROKER');
});
pm.test('current user authorization versions are present', function () {
  const body = pm.response.json();
  pm.expect(body.data.permissionRevision).to.be.above(0);
  pm.expect(body.data.credentialEpoch).to.be.above(0);
});
'@ -replace "`r|`n", ' '
    # Username remains an environment value so neither the response nor test output reveals credentials.
    $me = Invoke-PostmanRequest 'GET' "$BaseUrl/auth/me" @($bearerHeader) $null $postmanEnvironmentPath $meAssertion
    Assert-PostmanResult $me 200 '/auth/me'
    if ($me.Body.data.username -ne $username -or $me.Body.data.roleCode -ne 'BROKER' -or
            $me.Body.data.businessAdmin -ne $false -or $me.Body.data.roles -notcontains 'BROKER') {
        throw '/auth/me returned an identity or role that does not match the acceptance account.'
    }

    Start-Sleep -Seconds 2
    $oldRefreshToken = $refreshToken
    $refreshBody = '{"refreshToken":"{{acceptance_refresh_token}}"}'
    $refreshAssertion = @'
pm.test('refresh rotates both tokens and preserves authorization fields', function () {
  const body = pm.response.json();
  pm.expect(pm.response.code).to.eql(200);
  pm.expect(body.success).to.eql(true);
  pm.expect(body.data.accessToken).to.be.a('string').and.not.empty;
  pm.expect(body.data.refreshToken).to.be.a('string').and.not.empty;
  pm.expect(body.data.roleCode).to.eql('BROKER');
  pm.expect(body.data.businessAdmin).to.eql(false);
  pm.expect(body.data.roles).to.include('BROKER');
  pm.expect(body.data.permissionRevision).to.be.above(0);
  pm.expect(body.data.credentialEpoch).to.be.above(0);
});
'@ -replace "`r|`n", ' '
    $refresh = Invoke-PostmanRequest 'POST' "$BaseUrl/auth/refresh" @('Content-Type:application/json') $refreshBody $postmanEnvironmentPath $refreshAssertion
    Assert-PostmanResult $refresh 200 'refresh token rotation'
    $accessToken = [string]$refresh.Body.data.accessToken
    $refreshToken = [string]$refresh.Body.data.refreshToken
    if ($refreshToken -eq $oldRefreshToken) { throw 'Auth refresh did not rotate the refresh token.' }

    $environmentObject = Get-Content -Raw $postmanEnvironmentPath | ConvertFrom-Json
    $environmentObject.values = @(
        @{ key = 'acceptance_access_token'; value = $accessToken; type = 'secret'; enabled = $true },
        @{ key = 'acceptance_refresh_token'; value = $refreshToken; type = 'secret'; enabled = $true },
        @{ key = 'acceptance_old_refresh_token'; value = $oldRefreshToken; type = 'secret'; enabled = $true },
        @{ key = 'acceptance_username'; value = $username; type = 'secret'; enabled = $true }
    )
    [System.IO.File]::WriteAllText($postmanEnvironmentPath, ($environmentObject | ConvertTo-Json -Depth 5), [System.Text.UTF8Encoding]::new($false))
    Set-PrivateFileAcl $postmanEnvironmentPath

    $oldRefreshAssertion = @'
pm.test('rotated refresh token cannot be reused', function () {
  const body = pm.response.json();
  pm.expect(pm.response.code).to.eql(200);
  pm.expect(body.success).to.eql(false);
  pm.expect(body.code).to.eql('AUTH_401');
});
'@ -replace "`r|`n", ' '
    $oldRefreshBody = '{"refreshToken":"{{acceptance_old_refresh_token}}"}'
    $oldRefresh = Invoke-PostmanRequest 'POST' "$BaseUrl/auth/refresh" @('Content-Type:application/json') $oldRefreshBody $postmanEnvironmentPath $oldRefreshAssertion
    Assert-PostmanResult $oldRefresh 200 'reused refresh token'

    $refreshedMeAssertion = @'
pm.test('refreshed access token authenticates the same actor', function () {
  const body = pm.response.json();
  pm.expect(pm.response.code).to.eql(200);
  pm.expect(body.success).to.eql(true);
  pm.expect(body.data.roleCode).to.eql('BROKER');
  pm.expect(body.data.businessAdmin).to.eql(false);
  pm.expect(body.data.roles).to.include('BROKER');
});
'@ -replace "`r|`n", ' '
    $refreshedMe = Invoke-PostmanRequest 'GET' "$BaseUrl/auth/me" @($bearerHeader) $null $postmanEnvironmentPath $refreshedMeAssertion
    Assert-PostmanResult $refreshedMe 200 'refreshed /auth/me'

    $agentAssertion = @'
pm.test('Agent accepts a signed broker request with workflow read permission', function () {
  const body = pm.response.json();
  pm.expect(pm.response.code).to.eql(200);
  pm.expect(body.success).to.eql(true);
});
'@ -replace "`r|`n", ' '
    $agent = Invoke-PostmanRequest 'GET' "$BaseUrl/agent/supervisor/approvals/pending?page=1&pageSize=20" @($bearerHeader) $null $postmanEnvironmentPath $agentAssertion
    Assert-PostmanResult $agent 200 'Agent protected read'
    if ($agent.Body.success -ne $true) { throw 'Agent rejected a valid signed broker authorization context.' }

    $anonymousAssertion = @'
pm.test('spoofed identity without a bearer token is rejected', function () {
  pm.expect(pm.response.code).to.eql(401);
});
'@ -replace "`r|`n", ' '
    $anonymous = Invoke-PostmanRequest 'GET' "$BaseUrl/agent/supervisor/approvals/pending?page=1&pageSize=20" @('X-User-Id:1', 'X-Authenticated-Principal:true') $null $null $anonymousAssertion
    Assert-PostmanResult $anonymous 401 'anonymous spoof attempt'

    $substitutionAssertion = @'
pm.test('request-supplied userId cannot replace the signed actor', function () {
  pm.expect(pm.response.code).to.eql(403);
});
'@ -replace "`r|`n", ' '
    $substitution = Invoke-PostmanRequest 'GET' "$BaseUrl/agent/supervisor/approvals/pending?userId=999999999&page=1&pageSize=20" @($bearerHeader) $null $postmanEnvironmentPath $substitutionAssertion
    Assert-PostmanResult $substitution 403 'request identity substitution attempt'

    $logoutAssertion = @'
pm.test('logout accepts the current bearer token', function () {
  const body = pm.response.json();
  pm.expect(pm.response.code).to.eql(200);
  pm.expect(body.success).to.eql(true);
});
'@ -replace "`r|`n", ' '
    $logout = Invoke-PostmanRequest 'POST' "$BaseUrl/auth/logout" @($bearerHeader) $null $postmanEnvironmentPath $logoutAssertion
    Assert-PostmanResult $logout 200 'logout'

    $revokedAssertion = @'
pm.test('logout revokes the access token for subsequent requests', function () {
  pm.expect(pm.response.code).to.eql(401);
});
'@ -replace "`r|`n", ' '
    $revoked = Invoke-PostmanRequest 'GET' "$BaseUrl/auth/me" @($bearerHeader) $null $postmanEnvironmentPath $revokedAssertion
    Assert-PostmanResult $revoked 401 'revoked access token'

    $assertionTotal = $login.Total + $me.Total + $refresh.Total + $oldRefresh.Total + $refreshedMe.Total +
            $agent.Total + $anonymous.Total + $substitution.Total + $logout.Total + $revoked.Total
    Write-Output "Postman API acceptance passed: login $($login.Status), initial/refreshed /auth/me $($me.Status)/$($refreshedMe.Status), refresh/reuse $($refresh.Status)/$($oldRefresh.Status), Agent read $($agent.Status), spoof/substitution $($anonymous.Status)/$($substitution.Status), logout/revoked token $($logout.Status)/$($revoked.Status); $assertionTotal assertions passed."
}
finally {
    foreach ($path in @($responsePath, $postmanEnvironmentPath, (Join-Path $privateDir 'nonsecret-probe.json'))) {
        if ([System.IO.File]::Exists($path)) { [System.IO.File]::Delete($path) }
    }
    $username = $null
    $password = $null
    $accessToken = $null
    $refreshToken = $null
    $oldRefreshToken = $null
    $loginBody = $null
    $refreshBody = $null
    $environmentObject = $null
    $login = $null
    $me = $null
    $refresh = $null
    $oldRefresh = $null
    $refreshedMe = $null
    $agent = $null
    $anonymous = $null
    $substitution = $null
    $logout = $null
    $revoked = $null
    Pop-Location
}
