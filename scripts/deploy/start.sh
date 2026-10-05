#!/usr/bin/env bash
# 启动指定档位的完整环境，并等待全部容器健康。
# 首次启动会构建镜像（容器内 Maven 打包，耗时较长）；重复启动可加 --no-build 跳过。
#
# 用法：start.sh [--profile minimal|full|mcp] [--with-mcp] [--no-build] [--wait-timeout SEC] [--no-wait]
#   --profile       启动档位（默认 full；minimal 只含 MySQL/Nacos/认证/网关）
#   --with-mcp      附加启动 MySQL MCP 只读服务（mcp profile）
#   --no-build      跳过镜像构建，直接使用本地已有镜像
#   --wait-timeout  健康等待超时秒数（默认 600；full 档首次冷启动建议加大）
#   --no-wait       启动后不等待健康检查，立即返回
set -u -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

PROFILE="full"
WITH_MCP=0
NO_BUILD=0
WAIT_TIMEOUT=600
NO_WAIT=0

while [ $# -gt 0 ]; do
  case "$1" in
    --profile) PROFILE="$2"; shift 2 ;;
    --with-mcp) WITH_MCP=1; shift ;;
    --no-build) NO_BUILD=1; shift ;;
    --wait-timeout) WAIT_TIMEOUT="$2"; shift 2 ;;
    --no-wait) NO_WAIT=1; shift ;;
    -h|--help) grep '^#' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' | tail -n +2; exit 0 ;;
    *) die "未知参数：$1" ;;
  esac
done
case "$PROFILE" in minimal|full|mcp) ;; *) die "--profile 只支持 minimal|full|mcp" ;;
esac
case "$WAIT_TIMEOUT" in ''|*[!0-9]*) die "--wait-timeout 需要非负整数秒" ;; esac

cd "$BK_REPO_ROOT"
require_docker

if [ ! -f "$BK_ENV_FILE" ]; then
  log_warn "尚未初始化 .env，将使用 compose 默认值启动。建议先运行 scripts/deploy/init-environment.sh 生成随机密钥。"
fi

profile_flags=(--profile "$PROFILE")
[ "$WITH_MCP" -eq 1 ] && profile_flags+=(--profile mcp)

log_info "启动 ${PROFILE} 档位环境（$( [ "$NO_BUILD" -eq 1 ] && echo '跳过构建' || echo '包含镜像构建' )）…"
build_flags=()
[ "$NO_BUILD" -eq 1 ] || build_flags+=(--build)
if ! docker compose "${profile_flags[@]}" up -d "${build_flags[@]+"${build_flags[@]}"}"; then
  die "docker compose up 失败，请查看上方 compose 输出定位（常见原因：端口占用、镜像构建失败、磁盘不足）。"
fi

print_endpoints() { # $1=profile
  local profile="$1"
  log_info "验证入口："
  log_info "  网关健康检查     http://127.0.0.1:$(env_value "$BK_ENV_FILE" GATEWAY_EXPOSED_PORT 5010)/gateway/health"
  log_info "  认证服务健康检查 http://127.0.0.1:$(env_value "$BK_ENV_FILE" AUTH_EXPOSED_PORT 9101)/auth/health"
  log_info "  Nacos 控制台     http://127.0.0.1:$(env_value "$BK_ENV_FILE" NACOS_CONSOLE_EXPOSED_PORT 18080)/"
  if [ "$profile" = "full" ]; then
    log_info "  MinIO 控制台     http://127.0.0.1:$(env_value "$BK_ENV_FILE" MINIO_CONSOLE_EXPOSED_PORT 19001)/"
    log_info "  Elasticsearch    http://127.0.0.1:$(env_value "$BK_ENV_FILE" ELASTICSEARCH_EXPOSED_PORT 9200)/"
    log_info "  Milvus 健康检查  http://127.0.0.1:$(env_value "$BK_ENV_FILE" MILVUS_HEALTH_EXPOSED_PORT 9091)/healthz"
  fi
  if [ "$WITH_MCP" -eq 1 ] || [ "$profile" = "mcp" ]; then
    log_info "  MySQL MCP        http://127.0.0.1:$(env_value "$BK_ENV_FILE" MYSQL_MCP_EXPOSED_PORT 18081)/mcp"
  fi
}

if [ "$NO_WAIT" -eq 1 ]; then
  log_info "已提交启动（--no-wait），未等待健康检查。"
  print_endpoints "$PROFILE"
  exit 0
fi

log_info "等待容器健康（超时 ${WAIT_TIMEOUT}s）…"
if wait_all_healthy "$WAIT_TIMEOUT" "${profile_flags[@]}"; then
  log_info "环境启动完成。"
  print_endpoints "$PROFILE"
else
  log_error "部分容器未在超时内就绪，用以下命令排查："
  log_error "  docker compose ${profile_flags[*]} ps"
  log_error "  docker compose logs --tail=100 <服务名>"
  exit 1
fi
