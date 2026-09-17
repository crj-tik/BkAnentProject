[CmdletBinding()]
param(
    [ValidateSet('local-auth', 'distributed')]
    [string]$Mode = 'local-auth'
)

$ErrorActionPreference = 'Stop'

function Test-TcpPort {
    param(
        [Parameter(Mandatory = $true)][string]$HostName,
        [Parameter(Mandatory = $true)][int]$Port,
        [int]$TimeoutMilliseconds = 1000
    )

    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $connection = $client.ConnectAsync($HostName, $Port)
        if (-not $connection.Wait($TimeoutMilliseconds)) {
            return $false
        }
        return $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Get-EndpointParts {
    param([Parameter(Mandatory = $true)][string]$Endpoint)

    if ($Endpoint -notmatch '^(?<host>[^:]+):(?<port>\d+)$') {
        throw "Invalid endpoint '$Endpoint'. Expected host:port."
    }
    return [pscustomobject]@{
        Host = $Matches.host
        Port = [int]$Matches.port
    }
}

function Get-EnvironmentValue {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$DefaultValue
    )

    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($value)) {
        return $DefaultValue
    }
    return $value
}

$checks = [System.Collections.Generic.List[object]]::new()

if ($Mode -eq 'local-auth') {
    $checks.Add([pscustomobject]@{
        Name = 'Java executable'
        Endpoint = 'PATH'
        Available = $null -ne (Get-Command java -ErrorAction SilentlyContinue)
    })
    $checks.Add([pscustomobject]@{
        Name = 'Maven executable'
        Endpoint = 'PATH'
        Available = $null -ne (Get-Command mvn -ErrorAction SilentlyContinue)
    })
    Write-Host 'local-auth uses embedded H2 and does not require Nacos, MySQL, Redis, or other remote services.'
} else {
    $nacosEndpoint = Get-EndpointParts (Get-EnvironmentValue 'NACOS_SERVER' '127.0.0.1:8848')
    $dependencies = @(
        [pscustomobject]@{ Name = 'Nacos'; Host = $nacosEndpoint.Host; Port = $nacosEndpoint.Port },
        [pscustomobject]@{ Name = 'MySQL'; Host = (Get-EnvironmentValue 'MYSQL_HOST' '127.0.0.1'); Port = [int](Get-EnvironmentValue 'MYSQL_PORT' '3306') },
        [pscustomobject]@{ Name = 'Redis'; Host = (Get-EnvironmentValue 'REDIS_HOST' '127.0.0.1'); Port = [int](Get-EnvironmentValue 'REDIS_PORT' '6379') },
        [pscustomobject]@{ Name = 'RocketMQ nameserver'; Host = (Get-EnvironmentValue 'ROCKETMQ_NAMESRV_HOST' '127.0.0.1'); Port = [int](Get-EnvironmentValue 'ROCKETMQ_NAMESRV_PORT' '9876') },
        [pscustomobject]@{ Name = 'Milvus'; Host = (Get-EnvironmentValue 'MILVUS_HOST' '127.0.0.1'); Port = [int](Get-EnvironmentValue 'MILVUS_PORT' '19530') },
        [pscustomobject]@{ Name = 'MinIO'; Host = (Get-EnvironmentValue 'MINIO_HOST' '127.0.0.1'); Port = [int](Get-EnvironmentValue 'MINIO_PORT' '9000') },
        [pscustomobject]@{ Name = 'Elasticsearch'; Host = (Get-EnvironmentValue 'ES_HOST' '127.0.0.1'); Port = [int](Get-EnvironmentValue 'ES_PORT' '9200') }
    )
    foreach ($dependency in $dependencies) {
        $checks.Add([pscustomobject]@{
            Name = $dependency.Name
            Endpoint = "$($dependency.Host):$($dependency.Port)"
            Available = Test-TcpPort $dependency.Host $dependency.Port
        })
    }
}

Write-Host "Environment check: $Mode"
$checks | Format-Table -AutoSize

$failed = @($checks | Where-Object { -not $_.Available })
if ($failed.Count -gt 0) {
    Write-Error ("Unavailable prerequisites: " + (($failed | ForEach-Object { $_.Name }) -join ', '))
    exit 1
}
Write-Host 'All checked prerequisites are available.'
