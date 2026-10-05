#!/usr/bin/env bash
# 分布式环境前置可达性检查（scripts/local/check-environment.ps1 的 Linux 版）。
# 用于裸机分布式启动（mvn spring-boot:run -Dspring-boot.run.profiles=distributed）或
# Docker 部署后确认基础设施端口已经就绪。地址与端口从 .env 读取。
#
# 用法：check-environment.sh
#   无参数。读取 .env（缺失时先运行 scripts/deploy/init-environment.sh）。
set -u -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

cd "$BK_REPO_ROOT"

[ -f "$BK_ENV_FILE" ] || die "缺少 .env（${BK_ENV_FILE}）。先运行 scripts/deploy/init-environment.sh 或从 .env.example 手工复制。"

failures=0

check_tcp() { # $1=名称 $2=host $3=port
  local name="$1" host="$2" port="$3" status="不可达"
  if [ -z "$port" ] || [ "$port" = "0" ]; then
    printf '  %-22s %-24s %s\n' "$name" "${host}:${port}" "跳过（未配置端口）"
    return 0
  fi
  if port_open "$port"; then status="可达"; else status="不可达"; failures=$((failures + 1)); fi
  printf '  %-22s %-24s %s\n' "$name" "${host}:${port}" "$status"
}

check_cmd() { # $1=名称 $2=命令 $3=fail|warn（缺失时计为失败还是仅提示）
  local name="$1" cmd="$2" mode="$3" status="缺失"
  if command -v "$cmd" >/dev/null 2>&1; then
    status="存在"
  elif [ "$mode" = "fail" ]; then
    failures=$((failures + 1))
  fi
  printf '  %-22s %-24s %s\n' "$name" "$cmd" "$status"
}

nacos_endpoint="$(env_value "$BK_ENV_FILE" NACOS_SERVER "127.0.0.1:8848")"
case "$nacos_endpoint" in
  *:*) nacos_host="${nacos_endpoint%%:*}"; nacos_port="${nacos_endpoint##*:}" ;;
  *)   nacos_host="$nacos_endpoint"; nacos_port="8848" ;;
esac

echo "分布式环境可达性检查（依据 .env，$(date '+%F %T')）"
printf '  %-22s %-24s %s\n' "检查项" "地址" "状态"

check_tcp "Nacos"          "$nacos_host" "$nacos_port"
check_tcp "MySQL"          "$(env_value "$BK_ENV_FILE" MYSQL_HOST "127.0.0.1")" "$(env_value "$BK_ENV_FILE" MYSQL_PORT 3306)"
check_tcp "Redis"          "$(env_value "$BK_ENV_FILE" REDIS_HOST "127.0.0.1")" "$(env_value "$BK_ENV_FILE" REDIS_PORT 6379)"
check_tcp "RocketMQ"       "$(env_value "$BK_ENV_FILE" ROCKETMQ_NAMESRV_HOST "127.0.0.1")" "$(env_value "$BK_ENV_FILE" ROCKETMQ_NAMESRV_PORT 9876)"
check_tcp "Milvus"         "$(env_value "$BK_ENV_FILE" MILVUS_HOST "127.0.0.1")" "$(env_value "$BK_ENV_FILE" MILVUS_PORT 19530)"
check_tcp "MinIO"          "$(env_value "$BK_ENV_FILE" MINIO_HOST "127.0.0.1")" "$(env_value "$BK_ENV_FILE" MINIO_PORT 19000)"
check_tcp "Elasticsearch"  "$(env_value "$BK_ENV_FILE" ES_HOST "127.0.0.1")" "$(env_value "$BK_ENV_FILE" ES_PORT 9200)"

# 裸机启动才需要本机 Java/Maven；Docker 部署可忽略这两项缺失（只提示，不计失败）。
echo
echo "本机构建工具（仅裸机 mvn 启动需要；Docker 部署可忽略缺失）："
check_cmd "Java" java warn
check_cmd "Maven" mvn warn

echo
if [ "$failures" -gt 0 ]; then
  log_error "检查未通过：${failures} 项不可用。请先启动对应基础设施或运行 scripts/deploy/start.sh。"
  exit 1
fi
log_info "全部检查项可用。"
