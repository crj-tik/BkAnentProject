#!/usr/bin/env bash
# 查看环境状态：容器列表 + 关键 HTTP 健康端点实测。
#
# 用法：status.sh [--with-mcp] [--no-http]
#   --with-mcp  同时检查 MySQL MCP 容器
#   --no-http   只看容器状态，不做 HTTP 健康探测
set -u -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

WITH_MCP=0
NO_HTTP=0

while [ $# -gt 0 ]; do
  case "$1" in
    --with-mcp) WITH_MCP=1; shift ;;
    --no-http) NO_HTTP=1; shift ;;
    -h|--help) grep '^#' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' | tail -n +2; exit 0 ;;
    *) die "未知参数：$1" ;;
  esac
done

cd "$BK_REPO_ROOT"
require_docker

profile_flags=(--profile full)
[ "$WITH_MCP" -eq 1 ] && profile_flags+=(--profile mcp)

docker compose "${profile_flags[@]}" ps
echo

if [ "$NO_HTTP" -eq 1 ]; then
  exit 0
fi

if ! command -v curl >/dev/null 2>&1; then
  log_warn "宿主机没有 curl，跳过 HTTP 健康探测（容器内健康检查仍由 compose 管理）。"
  exit 0
fi

probe() { # $1=名称 $2=URL
  local name="$1" url="$2" body rc
  body="$(curl -fsS -m 5 "$url" 2>/dev/null)" && rc=0 || rc=$?
  if [ $rc -ne 0 ]; then
    printf '  %-20s %s  %s\n' "$name" "$url" "不可达"
  else
    printf '  %-20s %s  %s\n' "$name" "$url" "HTTP OK ${body:+— ${body}}"
  fi
}

echo "HTTP 健康探测："
probe "网关"       "http://127.0.0.1:$(env_value "$BK_ENV_FILE" GATEWAY_EXPOSED_PORT 5010)/gateway/health"
probe "认证服务"   "http://127.0.0.1:$(env_value "$BK_ENV_FILE" AUTH_EXPOSED_PORT 9101)/auth/health"
probe "Nacos 控制台" "http://127.0.0.1:$(env_value "$BK_ENV_FILE" NACOS_CONSOLE_EXPOSED_PORT 18080)/v3/console/health/readiness"
if [ -n "$(docker compose "${profile_flags[@]}" ps -q minio 2>/dev/null)" ]; then
  probe "MinIO"    "http://127.0.0.1:$(env_value "$BK_ENV_FILE" MINIO_EXPOSED_PORT 19000)/minio/health/live"
fi
if [ -n "$(docker compose "${profile_flags[@]}" ps -q elasticsearch 2>/dev/null)" ]; then
  probe "Elasticsearch" "http://127.0.0.1:$(env_value "$BK_ENV_FILE" ELASTICSEARCH_EXPOSED_PORT 9200)/"
fi
if [ "$WITH_MCP" -eq 1 ]; then
  probe "MySQL MCP" "http://127.0.0.1:$(env_value "$BK_ENV_FILE" MYSQL_MCP_EXPOSED_PORT 18081)/mcp"
fi
