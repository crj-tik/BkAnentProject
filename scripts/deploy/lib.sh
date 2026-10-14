#!/usr/bin/env bash
# BkAgent Linux 部署脚本公共库；只允许被其他脚本 source，不要直接执行。
# shellcheck shell=bash

# 故意不启用 set -e：预检类脚本需要收集全部失败项后统一退出，而不是在第一个失败处中断。
set -u -o pipefail

_BK_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BK_REPO_ROOT="$(cd "${_BK_LIB_DIR}/../.." && pwd)"
BK_ENV_FILE="${BK_ENV_FILE:-${BK_REPO_ROOT}/.env}"

# Keep an external, restricted runtime env usable without copying secrets into the repo.
compose() { docker compose --project-directory "$BK_REPO_ROOT" -f "$BK_REPO_ROOT/docker-compose.yml" --env-file "$BK_ENV_FILE" "$@"; }

log_info()  { printf '[INFO] %s\n' "$*"; }
log_warn()  { printf '[WARN] %s\n' "$*" >&2; }
log_error() { printf '[ERROR] %s\n' "$*" >&2; }
die() { log_error "$*"; exit 1; }

# 从 env 文件读取指定 KEY 的值（忽略注释与空白）；键不存在或值为空时输出默认值。
env_value() {
  local file="$1" key="$2" default="${3-}" line k v
  [ -f "$file" ] || { printf '%s' "$default"; return 0; }
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|\#*) continue ;; esac
    case "$line" in *=*) ;; *) continue ;; esac
    k="${line%%=*}"; v="${line#*=}"
    k="${k#"${k%%[![:space:]]*}"}"; k="${k%"${k##*[![:space:]]}"}"
    if [ "$k" = "$key" ]; then
      v="${v#"${v%%[![:space:]]*}"}"; v="${v%"${v##*[![:space:]]}"}"
      if [ "${#v}" -ge 2 ]; then
        case "$v" in
          \"*\") v="${v#\"}"; v="${v%\"}" ;;
          \'*\') v="${v#\'}"; v="${v%\'}" ;;
        esac
      fi
      if [ -z "$v" ]; then v="$default"; fi
      printf '%s' "$v"
      return 0
    fi
  done < "$file"
  printf '%s' "$default"
}

# 将 KEY=VALUE 写回 env 文件：已存在则原位替换（保持行序与注释），不存在则追加到末尾。
# 通过截断重写保留原文件 inode 与权限。
env_set() {
  local file="$1" key="$2" value="$3" tmp
  tmp="${file}.bktmp.$$"
  if grep -q "^[[:space:]]*${key}=" "$file" 2>/dev/null; then
    awk -v key="$key" -v value="$value" '
      !done && $0 ~ "^[[:space:]]*" key "=" { print key "=" value; done = 1; next }
      { print }
    ' "$file" > "$tmp" || return 1
    cat "$tmp" > "$file" || return 1
    rm -f "$tmp"
  else
    {
      printf '\n# Appended by scripts/deploy/init-environment.sh\n'
      printf '%s=%s\n' "$key" "$value"
    } >> "$file" || return 1
  fi
}

# 生成十六进制随机密钥；$1 = 字节数（默认 32）。
gen_secret() {
  local bytes="${1:-32}"
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex "$bytes"
  else
    od -An -N"$bytes" -tx1 /dev/urandom | tr -d ' \n'
  fi
}

# Bounded TCP probe of the configured host (never silently substitute localhost).
tcp_open() {
  local host="$1" port="$2"
  [[ "$port" =~ ^[0-9]+$ ]] && [ "$port" -ge 1 ] && [ "$port" -le 65535 ] || return 1
  command -v timeout >/dev/null 2>&1 || return 1
  timeout 3 bash -c 'exec 3<>"/dev/tcp/$1/$2"' _ "$host" "$port" 2>/dev/null
}
port_open() { tcp_open 127.0.0.1 "$1"; }

require_docker() {
  command -v docker >/dev/null 2>&1 || die "未检测到 docker。请先安装 Docker Engine（>= 24）与 Compose V2 插件，参见 docs/linux-deployment-init.md。"
  docker compose version >/dev/null 2>&1 || die "'docker compose version' 不可用，需要 Compose V2 插件（不支持 docker-compose v1）。"
}

# Elasticsearch / Milvus 要求宿主机 vm.max_map_count >= 262144。
# 返回 0=满足；1=不满足或无法读取（提示语由调用方决定）。
vm_max_map_count_ok() {
  local f="/proc/sys/vm/max_map_count" current
  [ -r "$f" ] || return 1
  current="$(cat "$f" 2>/dev/null || echo 0)"
  [ "${current:-0}" -ge "$1" ]
}

host_ram_kb() {
  awk '/^MemTotal:/ {print $2; exit}' /proc/meminfo 2>/dev/null || echo 0
}

# 等待 compose 环境内全部容器就绪。就绪定义：带健康检查的容器 healthy；无健康检查的
# running；退出码为 0 的一次性任务（config-init、minio-init 等）视为完成。
# 用法：wait_all_healthy TIMEOUT_SEC [docker compose 全局参数，如 --profile full]
# 返回：0=全部就绪 1=出现异常退出 2=超时
wait_all_healthy() {
  local timeout="$1"; shift
  local deadline=$(( $(date +%s) + timeout ))
  local -a flags=() services=()
  while [ "$#" -gt 0 ] && [ "$1" != -- ]; do flags+=("$1"); shift; done
  if [ "$#" -gt 0 ]; then shift; services=("$@"); fi
  local expected
  if [ "${#services[@]}" -gt 0 ]; then
    expected="${services[*]}"
  else
    expected="$(compose "${flags[@]}" config --services)" || return 1
  fi
  [ -n "$expected" ] || return 1
  while :; do
    local ids="" id starting=0 unhealthy=0 failed=0 total=0
    local state health exitcode name
    local service service_ids
    for service in $expected; do
      service_ids="$(compose "${flags[@]}" ps -a -q "$service" 2>/dev/null)" || return 1
      if [ -z "$service_ids" ]; then
        failed=$((failed + 1)); log_error "缺少预期服务实例：${service}"
      else
        ids="$ids $service_ids"
      fi
    done
    for id in $ids; do
      state="$(docker inspect -f '{{.State.Status}}' "$id" 2>/dev/null || echo unknown)"
      health="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$id" 2>/dev/null || echo unknown)"
      exitcode="$(docker inspect -f '{{.State.ExitCode}}' "$id" 2>/dev/null || echo 1)"
      name="$(docker inspect -f '{{.Name}}' "$id" 2>/dev/null | sed 's#^/##')"
      total=$((total + 1))
      # An old healthy result must not mask a stopped/restarting container.
      if [ "$state" != running ]; then
        case "$state:$exitcode:$name" in
          exited:0:*config-init*|exited:0:*minio-init*|exited:0:*elasticsearch-init*|exited:0:*mysql-mcp-init*) continue ;;
          created:*|restarting:*) starting=$((starting + 1)); continue ;;
          *) failed=$((failed + 1)); log_error "容器未运行：${name}（state=${state}, exit=${exitcode}）"; continue ;;
        esac
      fi
      case "$health" in
        healthy)
          continue ;;
        none)
          case "$state" in
            running) continue ;;
            exited)
              if [ "$exitcode" = "0" ]; then continue; fi
              failed=$((failed + 1)); log_error "容器异常退出：${name}（exit=${exitcode}）" ;;
            created|restarting|paused|unknown) starting=$((starting + 1)) ;;
            *) failed=$((failed + 1)); log_error "容器状态异常：${name}（state=${state}）" ;;
          esac ;;
        starting) starting=$((starting + 1)) ;;
        unhealthy) unhealthy=$((unhealthy + 1)) ;;
        *) starting=$((starting + 1)) ;;
      esac
    done
    if [ "$failed" -gt 0 ]; then return 1; fi
    if [ "$starting" -eq 0 ] && [ "$unhealthy" -eq 0 ] && [ "$total" -gt 0 ]; then
      log_info "全部 ${total} 个容器就绪（一次性初始化任务视为完成）。"
      return 0
    fi
    if [ "$(date +%s)" -ge "$deadline" ]; then
      log_warn "等待超时：starting=${starting} unhealthy=${unhealthy} total=${total}"
      return 2
    fi
    sleep 5
  done
}
