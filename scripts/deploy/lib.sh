#!/usr/bin/env bash
# BkAgent Linux 部署脚本公共库；只允许被其他脚本 source，不要直接执行。
# shellcheck shell=bash

# 故意不启用 set -e：预检类脚本需要收集全部失败项后统一退出，而不是在第一个失败处中断。
set -u -o pipefail

_BK_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BK_REPO_ROOT="$(cd "${_BK_LIB_DIR}/../.." && pwd)"
BK_ENV_FILE="${BK_REPO_ROOT}/.env"

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

# 127.0.0.1 上该 TCP 端口是否已有监听（用于服务可达性判断）。
port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

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
  while :; do
    local ids="" id starting=0 unhealthy=0 failed=0 total=0
    local state health exitcode name
    ids="$(docker compose "$@" ps -q 2>/dev/null || true)"
    for id in $ids; do
      state="$(docker inspect -f '{{.State.Status}}' "$id" 2>/dev/null || echo unknown)"
      health="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$id" 2>/dev/null || echo unknown)"
      exitcode="$(docker inspect -f '{{.State.ExitCode}}' "$id" 2>/dev/null || echo 1)"
      name="$(docker inspect -f '{{.Name}}' "$id" 2>/dev/null | sed 's#^/##')"
      total=$((total + 1))
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
