#!/usr/bin/env bash
# 停止环境。默认只停止容器、保留容器与数据卷；删除容器或数据卷必须显式加参数。
# 说明：full 档位包含 minimal 全部服务，--profile full 可停止两种档位启动的所有容器。
#
# 用法：stop.sh [--profile minimal|full|mcp] [--with-mcp] [--down] [--down-volumes --yes]
#   --profile       目标档位（默认 full）
#   --with-mcp      连同 MySQL MCP 容器一起停止
#   --down          停止并删除容器（保留数据卷；等价 docker compose down）
#   --down-volumes  停止并删除容器与全部数据卷（销毁数据，必须同时加 --yes）
set -u -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

PROFILE="full"
WITH_MCP=0
DOWN=0
DOWN_VOLUMES=0
ASSUME_YES=0

while [ $# -gt 0 ]; do
  case "$1" in
    --profile) PROFILE="$2"; shift 2 ;;
    --with-mcp) WITH_MCP=1; shift ;;
    --down) DOWN=1; shift ;;
    --down-volumes) DOWN_VOLUMES=1; DOWN=1; shift ;;
    --yes) ASSUME_YES=1; shift ;;
    -h|--help) grep '^#' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' | tail -n +2; exit 0 ;;
    *) die "未知参数：$1" ;;
  esac
done
case "$PROFILE" in minimal|full|mcp) ;; *) die "--profile 只支持 minimal|full|mcp" ;;
esac

if [ "$DOWN_VOLUMES" -eq 1 ] && [ "$ASSUME_YES" -ne 1 ]; then
  die "--down-volumes 会删除全部数据卷（MySQL/Nacos/Milvus/ES 等业务数据不可恢复），必须同时提供 --yes 明确确认。"
fi

cd "$BK_REPO_ROOT"
require_docker

profile_flags=(--profile "$PROFILE")
[ "$WITH_MCP" -eq 1 ] && profile_flags+=(--profile mcp)

action_args=(stop)
[ "$DOWN" -eq 1 ] && action_args=(down)
[ "$DOWN_VOLUMES" -eq 1 ] && action_args=(down -v)

if [ "$DOWN_VOLUMES" -eq 1 ]; then
  log_warn "即将删除容器与全部数据卷（docker compose down -v）。业务数据、Nacos 配置、Milvus/ES 数据将一并销毁。"
fi

log_info "执行：docker compose ${profile_flags[*]} ${action_args[*]}"
docker compose "${profile_flags[@]}" "${action_args[@]}"
rc=$?
[ $rc -eq 0 ] || { log_error "停止命令返回非零（rc=${rc}），可重试或用 docker compose ps 检查。"; exit $rc; }

if [ "$DOWN" -eq 0 ]; then
  log_info "已停止（容器与数据卷保留，重新启动用 scripts/deploy/start.sh --profile ${PROFILE}）。"
else
  log_info "已删除容器（数据卷保留；如需清理数据卷，另行 --down-volumes --yes）。"
fi
