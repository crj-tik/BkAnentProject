param()

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$jarPath = Join-Path $repoRoot 'rag-service\target\rag-service-1.0.0-SNAPSHOT.jar'
$probePath = Join-Path $PSScriptRoot 'RagRerankClientProbe.java'
$image = 'maven:3.9.9-eclipse-temurin-17'
$network = 'bkanent-rerank-dev'
$reranker = 'reranker-bge-v2-m3-reranker-1'

if (-not (Test-Path -LiteralPath $jarPath)) {
    throw 'RAG runtime jar is missing. Build rag-service with the repository-local Maven settings first.'
}
if (-not (Test-Path -LiteralPath $probePath)) {
    throw 'RAG rerank probe source is missing.'
}
if (-not (docker image inspect $image 2>$null)) {
    throw 'The local JDK probe image is not available; this script never pulls images.'
}
if (-not (docker network inspect $network 2>$null)) {
    throw 'The existing private Reranker network is not available.'
}
$health = docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $reranker 2>$null
if ($LASTEXITCODE -ne 0 -or $health -ne 'healthy') {
    throw 'The existing Reranker container is not healthy; no service container was started.'
}

# Resolve only the Reranker settings from the ignored local .env; never print their values.
$allowedKeys = @(
    'RAG_RERANK_ENDPOINT',
    'RAG_RERANK_MODEL',
    'RAG_RERANK_PROTOCOL',
    'RAG_RERANK_EXPECTED_REVISION',
    'RAG_RERANK_EXPECTED_ARTIFACT_DIGEST',
    'RAG_RERANK_API_KEY'
)
$dotenv = Join-Path $repoRoot '.env'
if (Test-Path -LiteralPath $dotenv) {
    foreach ($line in Get-Content -LiteralPath $dotenv) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
            $key = $Matches[1]
            if ($key -in $allowedKeys) {
                $value = $Matches[2].Trim()
                if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or
                        ($value.StartsWith("'") -and $value.EndsWith("'")))) {
                    $value = $value.Substring(1, $value.Length - 2)
                }
                if (-not [string]::IsNullOrWhiteSpace($value)) {
                    Set-Item -Path "Env:$key" -Value $value
                }
            }
        }
    }
}

$jarMount = (Resolve-Path -LiteralPath $jarPath).Path.Replace('\', '/')
$probeMount = (Resolve-Path -LiteralPath $probePath).Path.Replace('\', '/')
$containerScript = @'
set -eu
jar xf /input/rag.jar BOOT-INF/classes BOOT-INF/lib
test -d BOOT-INF/classes && test -d BOOT-INF/lib
mkdir -p classes
javac --release 17 -encoding UTF-8 -cp 'BOOT-INF/classes:BOOT-INF/lib/*' \
  -d classes /input/RagRerankClientProbe.java
exec java -Xms64m -Xmx256m -cp 'classes:BOOT-INF/classes:BOOT-INF/lib/*' \
  com.bkanent.rag.security.http.RagRerankClientProbe
'@

$dockerArgs = @(
    'run', '--rm', '--pull=never',
    '--network', $network,
    '--read-only', '--cap-drop=ALL', '--security-opt', 'no-new-privileges',
    '--memory', '768m', '--pids-limit', '128',
    '--tmpfs', '/work:rw,nosuid,nodev,size=512m',
    '--mount', "type=bind,source=$jarMount,target=/input/rag.jar,readonly",
    '--mount', "type=bind,source=$probeMount,target=/input/RagRerankClientProbe.java,readonly",
    '--env', 'RAG_RERANK_ENDPOINT',
    '--env', 'RAG_RERANK_MODEL',
    '--env', 'RAG_RERANK_PROTOCOL',
    '--env', 'RAG_RERANK_EXPECTED_REVISION',
    '--env', 'RAG_RERANK_EXPECTED_ARTIFACT_DIGEST',
    '--env', 'RAG_RERANK_API_KEY',
    '--workdir', '/work', '--entrypoint', 'sh', $image, '-eu', '-c', $containerScript
)

& docker @dockerArgs
if ($LASTEXITCODE -ne 0) {
    throw "RAG rerank client probe failed with exit code $LASTEXITCODE. The one-off runner was removed automatically."
}
Write-Output 'No project service containers were started; the bounded one-off runner was removed automatically.'
