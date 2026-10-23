#!/usr/bin/env bash
# Private opt-in process only. Never source .env, forward values on argv, build modules, or run Compose.
set -euo pipefail
set +x

if [[ $# -ne 0 || ${RAG_PROBE_ENABLED:-} != true ]]; then
    printf '%s\n' 'Private probe requires RAG_PROBE_ENABLED=true and no command-line arguments.' >&2
    exit 64
fi
: "${RAG_PROBE_DOCKER_NETWORK:?Set the existing private project Docker network}"
: "${RAG_PROBE_JDK_IMAGE:?Set an already available Java 17 JDK image}"
: "${RAG_PROBE_RUNTIME_JAR:?Set the absolute path to the newly packaged agent-service executable jar}"
[[ -f "$RAG_PROBE_RUNTIME_JAR" ]] || { printf '%s\n' 'Packaged runtime jar does not exist.' >&2; exit 66; }
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "$script_dir/../.." && pwd)
jar_dir=$(cd -- "$(dirname -- "$RAG_PROBE_RUNTIME_JAR")" && pwd)
runtime_jar="$jar_dir/$(basename -- "$RAG_PROBE_RUNTIME_JAR")"
probe_source="$script_dir/RagRuntimeProbe.java"
agent_identity_key="$repo_root/.secrets/service-identities/agent-service/private-key.pem"
public_keys_env="$repo_root/.secrets/service-identities/public-keys.env"
[[ -f "$agent_identity_key" && -f "$public_keys_env" ]] || {
    printf '%s\n' 'Generated service identity files are missing; run the local identity bootstrap first.' >&2
    exit 66
}
# Docker Desktop needs Windows host mount paths when Git Bash path conversion is disabled.
if command -v cygpath >/dev/null 2>&1; then
    runtime_jar=$(cygpath -m "$runtime_jar")
    probe_source=$(cygpath -m "$probe_source")
    agent_identity_key=$(cygpath -m "$agent_identity_key")
    public_keys_env=$(cygpath -m "$public_keys_env")
fi

# Pass names, not values. Docker inherits only this explicit process-environment allowlist.
env_names=(
    RAG_PROBE_ENABLED RAG_PROBE_MODE RAG_PROBE_OPERATION RAG_PROBE_ALLOW_MUTATIONS
    RAG_PROBE_DEADLINE_SECONDS RAG_PROBE_RPC_TIMEOUT_MILLIS RAG_PROBE_ACCESS_TOKEN
    RAG_PROBE_AUTH_DUBBO_URL RAG_PROBE_RAG_DUBBO_URL RAG_PROBE_DOMAIN_KEY_ID
    RAG_PROBE_SPACE_ID RAG_PROBE_SCHEMA_VERSION RAG_PROBE_DIMENSION
    RAG_PROBE_EVENT_ID RAG_PROBE_DOCUMENT_ID RAG_PROBE_A2A_URL RAG_PROBE_A2A_AUDIENCE
    RAG_PROBE_TASK_ID RAG_PROBE_CONTEXT_ID RAG_PROBE_PROMPT RAG_PROBE_BLOCKING RAG_PROBE_DEBUG
    LISTING_RAG_RPC_SECRET MEMORY_RAG_RPC_SECRET AGENT_A2A_SIGNING_SECRET
    AUTH_RAG_RPC_RESPONSE_KEY_ID AUTH_RAG_RPC_RESPONSE_SECRET AUTH_SERVICE_IDENTITY_KEY_ID
)
env_args=()
for name in "${env_names[@]}"; do
    if [[ -v "$name" ]]; then env_args+=(--env "$name"); fi
done
# Console.readPassword works only when an interactive terminal is explicitly present.
tty_args=()
if [[ -t 0 && -t 1 ]]; then tty_args=(-it); fi

MSYS_NO_PATHCONV=1 docker run --rm --pull=never \
    "${tty_args[@]}" --network "$RAG_PROBE_DOCKER_NETWORK" \
    --read-only --cap-drop=ALL --security-opt no-new-privileges \
    --memory 768m --pids-limit 256 \
    --tmpfs /work:rw,nosuid,nodev,size=512m --tmpfs /tmp:rw,nosuid,nodev,size=64m \
    --env-file "$public_keys_env" \
    --mount "type=bind,source=$runtime_jar,target=/input/runtime.jar,readonly" \
    --mount "type=bind,source=$probe_source,target=/input/RagRuntimeProbe.java,readonly" \
    --mount "type=bind,source=$agent_identity_key,target=/run/secrets/service-identity.pem,readonly" \
    "${env_args[@]}" --workdir /work --entrypoint sh "$RAG_PROBE_JDK_IMAGE" -eu -c '
        jar xf /input/runtime.jar BOOT-INF/classes BOOT-INF/lib
        test -d BOOT-INF/classes && test -d BOOT-INF/lib || {
            printf "%s\n" "Expected a Spring Boot executable runtime jar." >&2; exit 65;
        }
        test -f BOOT-INF/lib/a2a-java-sdk-client-0.2.5.Beta2-bk-transport1.jar || {
            printf "%s\n" "Packaged official transport patch is missing." >&2; exit 65;
        }
        for file in BOOT-INF/lib/a2a-java-sdk-client-*.jar; do
            test "$file" = BOOT-INF/lib/a2a-java-sdk-client-0.2.5.Beta2-bk-transport1.jar || {
                printf "%s\n" "Ambiguous official A2A client versions; rebuild the runtime package." >&2; exit 65;
            }
        done
        mkdir classes
        javac --release 17 -encoding UTF-8 -cp "BOOT-INF/classes:BOOT-INF/lib/*" \
            -d classes /input/RagRuntimeProbe.java
        exec java -Xmx512m -Duser.home=/work -Ddubbo.application.qos-enable=false \
            -cp "classes:BOOT-INF/classes:BOOT-INF/lib/*" com.bkanent.verification.RagRuntimeProbe
    '
