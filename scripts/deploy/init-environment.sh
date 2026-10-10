#!/usr/bin/env bash
# Linux 部署环境一键初始化。
# 职责：docker/compose 校验、内核参数与资源预检、端口冲突检查、
#       .env 引导（缺失密钥用随机值补齐，绝不覆盖已有值）、Nacos 配置完整性检查。
# 只做宿主机与配置层初始化，不启动任何容器；启动用 scripts/deploy/start.sh。
#
# 用法：init-environment.sh [--profile minimal|full|mcp] [--bind ADDR] [--fix-kernel] [--skip-docker]
#   --profile      目标部署档位，决定资源/端口/配置检查范围（默认 full）
#   --bind ADDR    将 .env 的 HOST_BIND_ADDRESS 设置为 ADDR（如 0.0.0.0，向局域网开放端口）
#   --fix-kernel   vm.max_map_count 不达标时通过 sudo 直接修正并持久化
#   --skip-docker  跳过 docker 检查（在无 docker 的中转机上预生成配置时使用）
set -u -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

PROFILE="full"
BIND_ADDR=""
FIX_KERNEL=0
SKIP_DOCKER=0

usage() {
  sed -n '2,11p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --profile) PROFILE="$2"; shift 2 ;;
    --bind) BIND_ADDR="$2"; shift 2 ;;
    --fix-kernel) FIX_KERNEL=1; shift ;;
    --skip-docker) SKIP_DOCKER=1; shift ;;
    -h|--help) usage 0 ;;
    *) die "未知参数：$1（-h 查看用法）" ;;
  esac
done
case "$PROFILE" in minimal|full|mcp) ;; *) die "--profile 只支持 minimal|full|mcp" ;; esac

cd "$BK_REPO_ROOT"

log_info "BkAgent Linux 部署初始化开始（profile=${PROFILE}，仓库根：${BK_REPO_ROOT}）"

# 1) 仓库部署工件完整性：compose 挂载的 SQL、Dockerfile、Nacos 配置目录必须就位。
missing_artifacts=0
for f in docker-compose.yml Dockerfile .env.example sql/mysql-init.sql docker/mysql/004-supervisor.sql; do
  if [ ! -f "$f" ]; then log_error "缺少部署工件：$f"; missing_artifacts=1; fi
done
[ -d nacos ] || { log_error "缺少 nacos/ 配置目录"; missing_artifacts=1; }
[ -d sql/migrations ] || { log_error "缺少 sql/migrations/ 目录"; missing_artifacts=1; }
[ "$missing_artifacts" -eq 0 ] || die "仓库工件不完整，请确认在完整源码仓库内执行。"

# 2) Docker 与 Compose V2。
if [ "$SKIP_DOCKER" -eq 1 ]; then
  log_warn "已按 --skip-docker 跳过 docker 检查（仅初始化配置时使用）"
else
  require_docker
  log_info "docker 与 compose 插件检查通过：$(docker compose version --short 2>/dev/null || echo 'unknown')"
fi

# 3) 内核参数：full 档位含 Elasticsearch/Milvus，宿主机必须满足 vm.max_map_count >= 262144。
if [ "$PROFILE" = "full" ]; then
  if [ ! -r /proc/sys/vm/max_map_count ]; then
    log_warn "无法读取 /proc/sys/vm/max_map_count，跳过内核参数检查"
  elif vm_max_map_count_ok 262144; then
    log_info "vm.max_map_count 满足要求（>= 262144）"
  else
    current="$(cat /proc/sys/vm/max_map_count 2>/dev/null || echo 'unknown')"
    log_warn "vm.max_map_count=${current}，低于 Elasticsearch/Milvus 要求的 262144"
    if [ "$FIX_KERNEL" -eq 1 ]; then
      sudo sysctl -w vm.max_map_count=262144 \
        && echo 'vm.max_map_count = 262144' | sudo tee /etc/sysctl.d/99-bkagent.conf >/dev/null \
        && log_info "已修正并持久化到 /etc/sysctl.d/99-bkagent.conf" \
        || die "内核参数修正失败，请手工执行：sudo sysctl -w vm.max_map_count=262144"
    else
      log_warn "修复方式：sudo sysctl -w vm.max_map_count=262144（持久化写入 /etc/sysctl.d/99-bkagent.conf），或重跑本脚本加 --fix-kernel"
    fi
  fi
fi

# 4) 资源预检（只告警不阻断）。
ram_kb="$(host_ram_kb)"
if [ "$ram_kb" -gt 0 ]; then
  ram_gb=$((ram_kb / 1024 / 1024))
  case "$PROFILE" in
    full)    [ "$ram_gb" -ge 16 ] || log_warn "内存 ${ram_gb}GB，full 档位建议 >= 16GB（Elasticsearch/Milvus/九个业务服务同机）" ;;
    minimal) [ "$ram_gb" -ge 4 ]  || log_warn "内存 ${ram_gb}GB，minimal 档位建议 >= 4GB" ;;
    mcp)     : ;;
  esac
else
  log_warn "无法读取 /proc/meminfo，跳过内存检查"
fi
disk_kb="$(df -Pk "$BK_REPO_ROOT" 2>/dev/null | awk 'NR==2 {print $4}')"
if [ -n "${disk_kb:-}" ] && [ "$disk_kb" -gt 0 ]; then
  disk_gb=$((disk_kb / 1024 / 1024))
  case "$PROFILE" in
    full)    [ "$disk_gb" -ge 30 ] || log_warn "仓库所在分区可用空间 ${disk_gb}GB，full 档位（含镜像与 ES/Milvus 数据卷）建议 >= 30GB" ;;
    minimal) [ "$disk_gb" -ge 10 ] || log_warn "仓库所在分区可用空间 ${disk_gb}GB，minimal 档位建议 >= 10GB" ;;
    mcp)     : ;;
  esac
else
  log_warn "无法读取磁盘可用空间，跳过磁盘检查"
fi

# 5) .env 仅引导端口/连接/密钥/启动参数；业务配置在 Nacos，不在此补齐或覆盖。
# 缺失则从 .env.example 复制；为空密钥生成随机值；绝不覆盖已有非空值。
if [ ! -f "$BK_ENV_FILE" ]; then
  cp .env.example "$BK_ENV_FILE" || die "复制 .env.example 失败"
  log_info "已从 .env.example 创建 .env"
else
  log_info "检测到已有 .env，保留全部现有取值"
fi

generated=0
fill_secret() { # $1=KEY $2=固定值或空；空则生成随机密钥
  local key="$1" fixed="${2-}" value
  if [ -n "$(env_value "$BK_ENV_FILE" "$key" '')" ]; then return 0; fi
  if [ -n "$fixed" ]; then value="$fixed"; else value="$(gen_secret 24)"; fi
  env_set "$BK_ENV_FILE" "$key" "$value" || die "写入 ${key} 失败"
  log_info "已生成缺失配置：${key}（值不回显，见 .env）"
  generated=$((generated + 1))
}
fill_secret MYSQL_ROOT_PASSWORD
fill_secret AUTH_TOKEN_SECRET
fill_secret MINIO_ROOT_USER "bkagent"
fill_secret MINIO_ROOT_PASSWORD
fill_secret MILVUS_MINIO_ROOT_USER "milvus-internal"
fill_secret MILVUS_MINIO_ROOT_PASSWORD
fill_secret MYSQL_MCP_PASSWORD
fill_secret MYSQL_MCP_TOKEN
[ "$generated" -gt 0 ] && log_info "共补齐 ${generated} 个缺失配置项" || log_info ".env 密钥项均已配置，无需补齐"
chmod 600 "$BK_ENV_FILE" 2>/dev/null || log_warn "无法将 .env 权限调整为 600，请手工执行 chmod 600 .env"

if [ -n "$BIND_ADDR" ]; then
  env_set "$BK_ENV_FILE" HOST_BIND_ADDRESS "$BIND_ADDR"
  log_warn "HOST_BIND_ADDRESS 已设为 ${BIND_ADDR}：基础设施端口将对非 loopback 地址开放，请确认网络边界（防火墙/安全组）后再继续"
fi

# 6) 端口冲突检查：本环境容器已运行时跳过（端口被占用属预期）。
any_running=0
if [ "$SKIP_DOCKER" -eq 0 ]; then
  if [ -n "$(docker compose --profile full --profile mcp ps -q 2>/dev/null)" ]; then any_running=1; fi
fi
if [ "$any_running" -eq 1 ]; then
  log_info "检测到本环境已有容器在运行，跳过端口检查（可用 scripts/deploy/status.sh 确认状态）"
else
  port_conflicts=""
  check_port() { # $1=说明 $2=端口
    local label="$1" port="$2"
    [ -n "$port" ] || return 0
    if port_open "$port"; then
      log_error "端口冲突：${label} 需要宿主机端口 ${port}，但已被占用"
      port_conflicts="${port_conflicts} ${port}"
    fi
  }
  check_port "Nacos API"        "$(env_value "$BK_ENV_FILE" NACOS_EXPOSED_PORT 8848)"
  check_port "Nacos 控制台"     "$(env_value "$BK_ENV_FILE" NACOS_CONSOLE_EXPOSED_PORT 18080)"
  check_port "Nacos gRPC"       "$(env_value "$BK_ENV_FILE" NACOS_GRPC_EXPOSED_PORT 9848)"
  check_port "Nacos gRPC TLS"   "$(env_value "$BK_ENV_FILE" NACOS_GRPC_TLS_EXPOSED_PORT 9849)"
  if [ "$PROFILE" = "minimal" ]; then
    check_port "auth-service"   "$(env_value "$BK_ENV_FILE" AUTH_EXPOSED_PORT 9101)"
    check_port "gateway"        "$(env_value "$BK_ENV_FILE" GATEWAY_EXPOSED_PORT 5010)"
  fi
  if [ "$PROFILE" = "full" ]; then
    check_port "Redis"              "$(env_value "$BK_ENV_FILE" REDIS_EXPOSED_PORT 6379)"
    check_port "RocketMQ NameServer" "$(env_value "$BK_ENV_FILE" ROCKETMQ_NAMESRV_EXPOSED_PORT 9876)"
    check_port "RocketMQ Broker"    "$(env_value "$BK_ENV_FILE" ROCKETMQ_BROKER_EXPOSED_PORT 10911)"
    check_port "RocketMQ Broker VIP" "$(env_value "$BK_ENV_FILE" ROCKETMQ_BROKER_VIP_EXPOSED_PORT 10909)"
    check_port "MinIO API"          "$(env_value "$BK_ENV_FILE" MINIO_EXPOSED_PORT 19000)"
    check_port "MinIO 控制台"       "$(env_value "$BK_ENV_FILE" MINIO_CONSOLE_EXPOSED_PORT 19001)"
    check_port "Elasticsearch"      "$(env_value "$BK_ENV_FILE" ELASTICSEARCH_EXPOSED_PORT 9200)"
    check_port "Milvus gRPC"        "$(env_value "$BK_ENV_FILE" MILVUS_EXPOSED_PORT 19530)"
    check_port "Milvus 健康检查"    "$(env_value "$BK_ENV_FILE" MILVUS_HEALTH_EXPOSED_PORT 9091)"
    check_port "Milvus etcd"        "$(env_value "$BK_ENV_FILE" MILVUS_ETCD_EXPOSED_PORT 2379)"
    check_port "auth-service"       "$(env_value "$BK_ENV_FILE" AUTH_EXPOSED_PORT 9101)"
    check_port "gateway"            "$(env_value "$BK_ENV_FILE" GATEWAY_EXPOSED_PORT 5010)"
  fi
  if [ "$PROFILE" = "mcp" ]; then
    check_port "MySQL MCP"      "$(env_value "$BK_ENV_FILE" MYSQL_MCP_EXPOSED_PORT 18081)"
  fi
  [ -z "$port_conflicts" ] || die "存在端口冲突（${port_conflicts}），请释放端口或在 .env 调整对应 *_EXPOSED_PORT 后重试。"
  log_info "端口检查通过，无冲突"
fi

# 7) Nacos 配置完整性：compose 的 config-init 会原样上传 nacos/*.yaml，缺文件即缺 data ID。
required_configs="mysql-common.yaml auth-service.yaml gateway.yaml"
if [ "$PROFILE" = "full" ]; then
  required_configs="$required_configs agent-service.yaml business-service.yaml compare-engine-service.yaml contract-service.yaml customer-service.yaml interview-service.yaml listing-master-service.yaml marketing-content-service.yaml media-worker-service.yaml memory-service.yaml notification-service.yaml promotion-service.yaml settlement-service.yaml"
fi
config_missing=0
for cfg in $required_configs; do
  if [ ! -f "nacos/${cfg}" ]; then log_error "缺少 Nacos 配置文件：nacos/${cfg}"; config_missing=1; fi
done
[ "$config_missing" -eq 0 ] && log_info "Nacos 配置文件完整（${PROFILE} 档位）" || true
[ "$config_missing" -eq 0 ] || die "Nacos 配置不完整，config-init 阶段会失败。"

# 8) 汇总与后续步骤。
log_info "初始化完成。要点回顾："
log_info "  - .env 已就绪（权限 600），真实模型密钥（DEEPSEEK_API_KEY / DASHSCOPE_API_KEY / AGENT_RAG_RERANK_API_KEY）仍为占位值，真实 AI 调用前必须替换"
if [ "$BIND_ADDR" != "0.0.0.0" ] && [ "$PROFILE" != "mcp" ]; then
  log_info "  - 默认只绑定 127.0.0.1；需要局域网访问时用 --bind 0.0.0.0 或手工改 .env 的 HOST_BIND_ADDRESS，并同步复查 A2A_PUBLIC_BASE_URL / MINIO_PUBLIC_BASE_URL"
fi
log_info "  - .env 只放端口/连接/密钥/启动参数；Token TTL、模型、排行榜、流式和搜索开关在 Nacos 维护"
log_info "  - 分布式生产必须在 Nacos 将 contract/notification/media/promotion 的 integration.mode 显式设为 real，并接入/选择真实 provider（合同需真实 OCR + 电子签）；不能在 .env 切换，不能削弱 readiness"
log_info "  - nacos/*.yaml 默认为 Docker 开发配置；生产导入前准备环境专属配置，避免 config-init 覆盖生产 data ID"
log_info "  - NACOS_USERNAME / NACOS_PASSWORD 可从 .env 传入应用容器；Admin 账号初始化与目标命名空间 Card 更新授权仍需另行完成"
if [ "$SKIP_DOCKER" -eq 0 ]; then
  log_info "下一步："
  log_info "  - 全新数据卷：直接 ./scripts/deploy/start.sh --profile ${PROFILE}"
  log_info "  - 已有数据卷（老库升级）：先 ./scripts/deploy/apply-migrations.sh，再启动"
  log_info "  - 启动后 ./scripts/deploy/status.sh 验证健康状态"
fi
