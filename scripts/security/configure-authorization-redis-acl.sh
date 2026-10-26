#!/usr/bin/env bash
set -Eeuo pipefail

# No xtrace: ACL passwords are sent through stdin and must never appear in command arguments or output.
fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

: "${REDIS_HOST:?Set REDIS_HOST}"
: "${REDIS_ADMIN_USER:?Set a named Redis ACL administrator}"
: "${REDIS_ADMIN_PASSWORD:?Load the Redis administrator password from a secret store}"
: "${AUTHZ_WRITER_PASSWORD:?Load AUTHZ_WRITER_PASSWORD from a secret store}"
: "${AUTHZ_READER_PASSWORD:?Load AUTHZ_READER_PASSWORD from a secret store}"

REDIS_PORT="${REDIS_PORT:-6379}"
AUTHZ_NAMESPACE="${AUTHZ_NAMESPACE:-authz}"
AUTHZ_CHANNEL="${AUTHZ_CHANNEL:-authz:versions}"
REDIS_TLS="${REDIS_TLS:-true}"

[[ "$REDIS_ADMIN_USER" != "default" && "$REDIS_ADMIN_USER" =~ ^[A-Za-z0-9._-]{1,64}$ ]] \
  || fail "REDIS_ADMIN_USER must be a named ACL administrator"
[[ "$REDIS_PORT" =~ ^[0-9]{1,5}$ ]] && (( REDIS_PORT >= 1 && REDIS_PORT <= 65535 )) \
  || fail "REDIS_PORT is invalid"
[[ "$AUTHZ_NAMESPACE" =~ ^[A-Za-z0-9_-]{1,40}$ ]] || fail "AUTHZ_NAMESPACE is invalid"
[[ "$AUTHZ_CHANNEL" =~ ^[A-Za-z0-9:_-]{1,96}$ ]] || fail "AUTHZ_CHANNEL is invalid"
[[ "$AUTHZ_WRITER_PASSWORD" =~ ^[A-Fa-f0-9]{64}$ ]] \
  || fail "AUTHZ_WRITER_PASSWORD must be a 32-byte random value encoded as hex"
[[ "$AUTHZ_READER_PASSWORD" =~ ^[A-Fa-f0-9]{64}$ ]] \
  || fail "AUTHZ_READER_PASSWORD must be a 32-byte random value encoded as hex"
[[ "$AUTHZ_WRITER_PASSWORD" != "$AUTHZ_READER_PASSWORD" ]] \
  || fail "Auth writer and Agent reader credentials must be different"

redis_args=(--no-auth-warning --raw -h "$REDIS_HOST" -p "$REDIS_PORT")
if [[ "$REDIS_TLS" == "true" ]]; then
  redis_args+=(--tls)
  if [[ -n "${REDIS_TLS_CA_CERT:-}" ]]; then redis_args+=(--cacert "$REDIS_TLS_CA_CERT"); fi
elif [[ "${REDIS_ALLOW_INSECURE_LOCAL:-false}" == "true" && "$REDIS_HOST" =~ ^(localhost|127\.0\.0\.1|::1)$ ]]; then
  :
else
  fail "TLS is required; plaintext is allowed only for an explicitly opted-in loopback Redis"
fi

run_admin() {
  REDISCLI_AUTH="$REDIS_ADMIN_PASSWORD" redis-cli "${redis_args[@]}" --user "$REDIS_ADMIN_USER" "$@"
}

server_info="$(run_admin INFO server)" || fail "Could not inspect the Redis server version"
redis_version="$(printf '%s\n' "$server_info" | sed -n 's/^redis_version://p' | tr -d '\r' | head -n 1)"
[[ "$redis_version" =~ ^[0-9]+\.[0-9]+ ]] || fail "Redis did not report a supported server version"
IFS=. read -r redis_major redis_minor _ <<< "$redis_version"
if (( redis_major < 7 || (redis_major == 7 && redis_minor < 2) )); then
  fail "Authorization Redis ACL requires Redis 7.2 or newer"
fi

writer_rules=(
  reset on resetpass resetkeys resetchannels -@all
  "%RW~${AUTHZ_NAMESPACE}:*:head"
  "%RW~${AUTHZ_NAMESPACE}:*:snapshot:*"
  "%RW~${AUTHZ_NAMESPACE}:*:fence"
  "%RW~${AUTHZ_NAMESPACE}:{active}:actors"
  "&${AUTHZ_CHANNEL}"
  +eval +evalsha +time +hmget +get +exists +set +expireat +hset
  +zremrangebyscore +zscore +zcard +zadd +expire +zrange +publish
)
reader_rules=(
  reset on resetpass resetkeys resetchannels -@all
  "%R~${AUTHZ_NAMESPACE}:*:head"
  "&${AUTHZ_CHANNEL}"
  +get +mget +subscribe +unsubscribe +ping +client\|setname +client\|setinfo
)

set_user() {
  local username="$1"
  local password="$2"
  shift 2
  local command="ACL SETUSER ${username}"
  local rule
  for rule in "$@"; do command+=" ${rule}"; done
  command+=" >${password}"
  local response
  response="$(printf '%s\n' "$command" | run_admin)" || fail "Redis rejected ACL provisioning for ${username}"
  [[ "$response" == "OK" ]] || fail "Redis did not confirm ACL provisioning for ${username}"
}

# Only these two dedicated principals are changed; the shared default and other app users remain untouched.
set_user authz-writer "$AUTHZ_WRITER_PASSWORD" "${writer_rules[@]}"
set_user agent-authz-reader "$AUTHZ_READER_PASSWORD" "${reader_rules[@]}"

"$(dirname -- "${BASH_SOURCE[0]}")/verify-authorization-redis-acl.sh"
