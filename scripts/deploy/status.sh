#!/usr/bin/env bash
# 查看环境状态：容器列表 + 关键 HTTP 健康端点实测。
#
# 用法：status.sh [--with-mcp] [--with-rag] [--no-http]
#   --with-mcp  同时检查 MySQL MCP 容器
#   --no-http   只看容器状态，不做 HTTP 健康探测
set -u -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

WITH_MCP=0
WITH_RAG=0
NO_HTTP=0
SERVICES=()

while [ $# -gt 0 ]; do
  case "$1" in
    --with-mcp) WITH_MCP=1; shift ;;
    --with-rag) WITH_RAG=1; shift ;;
    --service) [ "$#" -ge 2 ] || die "--service缺少名称"; SERVICES+=("$2"); shift 2 ;;
    --no-http) NO_HTTP=1; shift ;;
    -h|--help) grep '^#' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' | tail -n +2; exit 0 ;;
    *) die "未知参数：$1" ;;
  esac
done

cd "$BK_REPO_ROOT"
require_docker

profile_flags=(--profile full)
[ "$WITH_MCP" -eq 1 ] && profile_flags+=(--profile mcp)
if [ "$WITH_RAG" -eq 1 ]; then
  profile_flags+=(--profile rag)
  if [ "${#SERVICES[@]}" -gt 0 ] && ! printf '%s\n' "${SERVICES[@]}" | grep -qxF rag-service; then
    SERVICES+=(rag-service)
  fi
fi

compose "${profile_flags[@]}" config -q || exit 1
available="$(compose "${profile_flags[@]}" config --services)" || exit 1
for service in "${SERVICES[@]}"; do
  [[ "$service" =~ ^[a-z0-9-]+$ ]] && grep -qxF "$service" <<< "$available" || die "无效目标服务：${service}"
done
compose "${profile_flags[@]}" ps -a "${SERVICES[@]}" || exit 1
failures=0
wait_targets=()
[ "${#SERVICES[@]}" -eq 0 ] || wait_targets=(-- "${SERVICES[@]}")
wait_all_healthy 0 "${profile_flags[@]}" "${wait_targets[@]}" || failures=$((failures + 1))
echo

if [ "$NO_HTTP" -eq 1 ]; then
  [ "$failures" -eq 0 ]; exit $?
fi

if ! command -v curl >/dev/null 2>&1; then
  log_error "宿主机没有 curl，无法完成要求的 HTTP 健康探测。"
  exit 1
fi

probe() { # $1=名称 $2=URL
  local name="$1" url="$2" body rc
  body="$(curl -fsS -m 5 "$url" 2>/dev/null)" && rc=0 || rc=$?
  if [ $rc -ne 0 ]; then
    failures=$((failures + 1))
    printf '  %-20s %s  %s\n' "$name" "$url" "不可达"
  else
    printf '  %-20s %s  %s\n' "$name" "$url" "HTTP OK ${body:+— ${body}}"
  fi
}

echo "HTTP 健康探测："
probe "网关"       "http://127.0.0.1:$(env_value "$BK_ENV_FILE" GATEWAY_EXPOSED_PORT 5010)/gateway/health"
probe "认证服务"   "http://127.0.0.1:$(env_value "$BK_ENV_FILE" AUTH_EXPOSED_PORT 9101)/auth/health"
probe "Nacos 控制台" "http://127.0.0.1:$(env_value "$BK_ENV_FILE" NACOS_CONSOLE_EXPOSED_PORT 18080)/v3/console/health/readiness"
if [ -n "$(compose "${profile_flags[@]}" ps -a -q minio 2>/dev/null)" ]; then
  probe "MinIO"    "http://127.0.0.1:$(env_value "$BK_ENV_FILE" MINIO_EXPOSED_PORT 19000)/minio/health/live"
fi
if [ -n "$(compose "${profile_flags[@]}" ps -a -q elasticsearch 2>/dev/null)" ]; then
  probe "Elasticsearch" "http://127.0.0.1:$(env_value "$BK_ENV_FILE" ELASTICSEARCH_EXPOSED_PORT 9200)/"
fi
if [ "$WITH_RAG" -eq 1 ]; then
  probe "RAG 基础就绪" "http://127.0.0.1:$(env_value "$BK_ENV_FILE" RAG_MANAGEMENT_EXPOSED_PORT 9018)/actuator/health/readiness"
  log_warn "RAG 基础 readiness 检查安全配置、控制DB两表查询权限和至少三个已导出RPC provider，不直接探测 Nacos 或检索后端；空间/检索阶段能力与数据水位未由状态脚本验证；即使容器/HTTP门禁通过，也不等于真实混合检索或切流验收。"
fi
if [ "$WITH_MCP" -eq 1 ]; then
  probe "MySQL MCP" "http://127.0.0.1:$(env_value "$BK_ENV_FILE" MYSQL_MCP_EXPOSED_PORT 18081)/mcp"
fi
[ "$failures" -eq 0 ] || exit 1
