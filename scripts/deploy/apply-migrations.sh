#!/usr/bin/env bash
# 将 sql/migrations/ 下的增量 SQL 应用到已有 MySQL 数据卷。
# 背景：docker-compose 的 MySQL 首次初始化只执行 docker-entrypoint-initdb.d
#（mysql-init.sql + 两份 interview + 004-supervisor，004 内部会 SOURCE 两份 supervisor 迁移）；
# 已有数据卷不会重跑初始化，必须由本脚本按顺序补齐（与 docs/supervisor-orchestration-deployment.md 口径一致）。
#
# 注意：迁移文件并不保证幂等（部分为不带 IF NOT EXISTS 的 ALTER TABLE），
# 重复应用会报重复列/重复表错误；报此类错误通常说明该文件已应用过，跳过即可。
#
# 用法：apply-migrations.sh [--file PATH] [--dry-run] [--yes]
#   --file PATH  只应用指定迁移（相对仓库根或绝对路径）；缺省应用 sql/migrations/*.sql 全部
#   --dry-run    只列出将要应用的文件，不执行
#   --yes        非交互，逐个文件直接应用（出错即停）
set -u -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

ONLY_FILE=""
DRY_RUN=0
ASSUME_YES=0

while [ $# -gt 0 ]; do
  case "$1" in
    --file) ONLY_FILE="$2"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --yes) ASSUME_YES=1; shift ;;
    -h|--help) grep '^#' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' | tail -n +2; exit 0 ;;
    *) die "未知参数：$1" ;;
  esac
done

cd "$BK_REPO_ROOT"

if [ ! -d sql/migrations ]; then die "缺少 sql/migrations/ 目录"; fi

if [ -n "$ONLY_FILE" ]; then
  case "$ONLY_FILE" in
    /*) files="$ONLY_FILE" ;;
    *)  files="${BK_REPO_ROOT}/${ONLY_FILE}" ;;
  esac
  [ -f "$files" ] || die "指定的迁移文件不存在：${ONLY_FILE}"
else
  files="$(ls sql/migrations/*.sql 2>/dev/null | sort)" || true
  [ -n "$files" ] || die "sql/migrations/ 下没有迁移文件"
fi

if [ "$DRY_RUN" -eq 1 ]; then
  log_info "将按以下顺序应用迁移："
  printf '  %s\n' $files
  exit 0
fi

require_docker

MYSQL_ROOT_PASSWORD="$(env_value "$BK_ENV_FILE" MYSQL_ROOT_PASSWORD '')"
[ -n "$MYSQL_ROOT_PASSWORD" ] || die ".env 中 MYSQL_ROOT_PASSWORD 为空，无法连接 MySQL。已有数据卷的 root 密码请填入 .env 后重试。"

if ! docker compose ps -q mysql 2>/dev/null | grep -q .; then
  die "mysql 容器未运行。先启动环境（scripts/deploy/start.sh）或单独执行：docker compose up -d mysql"
fi

run_sql() { # $1=sql 文件
  docker compose exec -T -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql \
    mysql -uroot --default-character-set=utf8mb4 < "$1"
}

applied=0
for f in $files; do
  name="${f#sql/migrations/}"
  if [ "$ASSUME_YES" -ne 1 ]; then
    printf '应用 %s ? [Y/n] ' "$name"
    read -r answer
    case "$answer" in
      n*|N*) log_info "跳过 ${name}"; continue ;;
    esac
  fi
  log_info "应用 ${name} …"
  if run_sql "$f"; then
    log_info "完成：${name}"
    applied=$((applied + 1))
  else
    log_error "失败：${name}。若是重复列/重复表错误，通常表示此前已应用过；确认后可 --file 跳过它继续。"
    exit 1
  fi
done

log_info "迁移应用结束：本次成功 ${applied} 个文件。"
log_info "提醒：如需启用 MySQL MCP 只读账号，运行 docker compose --profile mcp run --rm mysql-mcp-init（已有数据卷同样适用）。"
