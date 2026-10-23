#!/usr/bin/env bash
# One-shot acceptance driver: starts a long STREAM task in the background, then immediately
# RESUBSCRIBEs to it while it is still running (SDK queue must be alive for tap()).
set -uo pipefail
export RAG_PROBE_ENABLED=true
export RAG_PROBE_DOCKER_NETWORK=bk-anent-dev_bk
export RAG_PROBE_JDK_IMAGE=maven@sha256:f58d59b6273e785ac0a4477f6e9b5ba1d7731c75b906c0f7b34076f1851318cc
export RAG_PROBE_RUNTIME_JAR=/d/project/BkAnentProject/agent-service/target/agent-service-1.0.0-SNAPSHOT.jar
export RAG_PROBE_MODE=A2A
export RAG_PROBE_DEADLINE_SECONDS=280
export RAG_PROBE_RPC_TIMEOUT_MILLIS=30000
export RAG_PROBE_AUTH_DUBBO_URL=dubbo://172.24.0.8:20881
export RAG_PROBE_A2A_URL=http://172.24.0.3:9004/a2a
export RAG_PROBE_A2A_AUDIENCE=listing-master-service
export AUTH_SERVICE_IDENTITY_KEY_ID="${AUTH_SERVICE_IDENTITY_KEY_ID:-auth-service-current}"
export RAG_PROBE_TASK_ID=acceptance-a2a-live-1
export RAG_PROBE_CONTEXT_ID=acceptance-a2a-live-ctx
export RAG_PROBE_PROMPT='请详细检索并对比朝南两室与朝北三室房源，生成完整报告'
export RAG_PROBE_BLOCKING=true
export AGENT_RAG_RPC_SECRET="${AGENT_RAG_RPC_SECRET:?}"
export AUTH_RAG_RPC_RESPONSE_KEY_ID="${AUTH_RAG_RPC_RESPONSE_KEY_ID:?}"
export AUTH_RAG_RPC_RESPONSE_SECRET="${AUTH_RAG_RPC_RESPONSE_SECRET:?}"
export RAG_PROBE_ACCESS_TOKEN="${RAG_PROBE_ACCESS_TOKEN:?}"
RUNNER="C:/Program Files/Git/usr/bin/bash.exe"
RAG_PROBE_OPERATION=STREAM RAG_PROBE_ALLOW_MUTATIONS=true \
"$RUNNER" /d/project/BkAnentProject/scripts/verification/run-rag-runtime-probe.sh &
STREAM_PID=$!
echo "stream_pid=$STREAM_PID"
sleep 5
export RAG_PROBE_OPERATION=RESUBSCRIBE RAG_PROBE_ALLOW_MUTATIONS=false
export RAG_PROBE_DEADLINE_SECONDS=200
"$RUNNER" /d/project/BkAnentProject/scripts/verification/run-rag-runtime-probe.sh
RESUB=$?
wait "$STREAM_PID"
STREAM=$?
echo "resubscribe_exit=$RESUB stream_exit=$STREAM"
