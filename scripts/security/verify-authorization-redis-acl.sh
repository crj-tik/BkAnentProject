#!/usr/bin/env bash
set -Eeuo pipefail

# No xtrace: tests use credentials from the environment and never print them.
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
[[ "$AUTHZ_WRITER_PASSWORD" =~ ^[A-Fa-f0-9]{64}$ && "$AUTHZ_READER_PASSWORD" =~ ^[A-Fa-f0-9]{64}$ ]] \
  || fail "Authorization user passwords must be 32-byte random values encoded as hex"

redis_args=(--no-auth-warning --raw --exit-on-error -h "$REDIS_HOST" -p "$REDIS_PORT")
if [[ "$REDIS_TLS" == "true" ]]; then
  redis_args+=(--tls)
  if [[ -n "${REDIS_TLS_CA_CERT:-}" ]]; then redis_args+=(--cacert "$REDIS_TLS_CA_CERT"); fi
elif [[ "${REDIS_ALLOW_INSECURE_LOCAL:-false}" == "true" && "$REDIS_HOST" =~ ^(localhost|127\.0\.0\.1|::1)$ ]]; then
  :
else
  fail "TLS is required; plaintext is allowed only for an explicitly opted-in loopback Redis"
fi

admin() { REDISCLI_AUTH="$REDIS_ADMIN_PASSWORD" redis-cli "${redis_args[@]}" --user "$REDIS_ADMIN_USER" "$@"; }

server_info="$(admin INFO server)" || fail "Could not inspect the Redis server version"
redis_version="$(printf '%s\n' "$server_info" | sed -n 's/^redis_version://p' | tr -d '\r' | head -n 1)"
[[ "$redis_version" =~ ^[0-9]+\.[0-9]+ ]] || fail "Redis did not report a supported server version"
IFS=. read -r redis_major redis_minor _ <<< "$redis_version"
if (( redis_major < 7 || (redis_major == 7 && redis_minor < 2) )); then
  fail "Authorization Redis ACL requires Redis 7.2 or newer"
fi

expect_allowed() {
  local username="$1"; shift
  local output
  output="$(admin ACL DRYRUN "$username" "$@" 2>&1)" \
    || fail "ACL denied an expected ${username} operation (${1})"
  [[ "$output" == "OK" ]] || fail "ACL dry-run did not confirm an expected ${username} operation (${1})"
}

expect_denied() {
  local username="$1"; shift
  local output
  if output="$(admin ACL DRYRUN "$username" "$@" 2>&1)"; then
    fail "ACL unexpectedly allowed ${username} to run ${1}"
  fi
  [[ "$output" == *"no permissions"* || "$output" == *"NOPERM"* ]] \
    || fail "ACL dry-run failed for an unexpected reason (${username} ${1})"
}

writer="authz-writer"
reader="agent-authz-reader"
head="${AUTHZ_NAMESPACE}:{42}:head"
snapshot="${AUTHZ_NAMESPACE}:{42}:snapshot:7"
fence="${AUTHZ_NAMESPACE}:{42}:fence"
active="${AUTHZ_NAMESPACE}:{active}:actors"

expect_allowed "$writer" EVAL "return redis.call('SET', KEYS[1], 'x')" 1 "$head"
expect_allowed "$writer" EVALSHA "0000000000000000000000000000000000000000" 1 "$head"
expect_allowed "$writer" TIME
expect_allowed "$writer" HMGET "$fence" revision epoch hash status checkedAt
expect_allowed "$writer" GET "$head"
expect_allowed "$writer" EXISTS "$head"
expect_allowed "$writer" SET "$snapshot" value EX 30
expect_allowed "$writer" EXPIREAT "$head" 1700000000
expect_allowed "$writer" HSET "$fence" revision 7
expect_allowed "$writer" ZREMRANGEBYSCORE "$active" -inf 0
expect_allowed "$writer" ZSCORE "$active" 42
expect_allowed "$writer" ZCARD "$active"
expect_allowed "$writer" ZADD "$active" 1700000000 42
expect_allowed "$writer" EXPIRE "$active" 301
expect_allowed "$writer" ZRANGE "$active" 0 99 WITHSCORES
expect_allowed "$writer" PUBLISH "$AUTHZ_CHANNEL" probe
expect_denied "$writer" SET "unrelated:{42}:head" value
expect_denied "$writer" PUBLISH "unrelated:channel" probe
expect_denied "$writer" SPUBLISH "$AUTHZ_CHANNEL" probe

expect_allowed "$reader" GET "$head"
expect_allowed "$reader" MGET "$head" "${AUTHZ_NAMESPACE}:{7}:head"
expect_allowed "$reader" SUBSCRIBE "$AUTHZ_CHANNEL"
expect_denied "$reader" GET "$snapshot"
expect_denied "$reader" SET "$head" value
expect_denied "$reader" EXPIRE "$head" 300
expect_denied "$reader" DEL "$head"
expect_denied "$reader" PUBLISH "$AUTHZ_CHANNEL" probe
expect_denied "$reader" SPUBLISH "$AUTHZ_CHANNEL" probe
expect_denied "$reader" SUBSCRIBE "unrelated:channel"
expect_denied "$reader" PSUBSCRIBE "authz:*"

subscribe_log="$(mktemp)"
subscriber_pid=""
cleanup() {
  if [[ -n "$subscriber_pid" ]]; then
    kill "$subscriber_pid" 2>/dev/null || true
    wait "$subscriber_pid" 2>/dev/null || true
  fi
  rm -f -- "$subscribe_log"
}
trap cleanup EXIT INT TERM

REDISCLI_AUTH="$AUTHZ_READER_PASSWORD" redis-cli "${redis_args[@]}" --user "$reader" \
  SUBSCRIBE "$AUTHZ_CHANNEL" >"$subscribe_log" 2>&1 &
subscriber_pid=$!
ready=false
for _ in {1..50}; do
  if grep -Fqx "$AUTHZ_CHANNEL" "$subscribe_log" && grep -Fq 'subscribe' "$subscribe_log"; then
    ready=true
    break
  fi
  sleep 0.1
done
[[ "$ready" == true ]] || fail "agent-authz-reader could not subscribe to the configured channel"

probe="authz-acl-smoke-${BASHPID}-${RANDOM}"
published="$(REDISCLI_AUTH="$AUTHZ_WRITER_PASSWORD" redis-cli "${redis_args[@]}" --user "$writer" \
  PUBLISH "$AUTHZ_CHANNEL" "$probe")" || fail "authz-writer could not publish to the configured channel"
[[ "$published" =~ ^[0-9]+$ && "$published" -ge 1 ]] \
  || fail "The configured channel did not have the authorized reader subscription"

received=false
for _ in {1..50}; do
  if grep -Fqx "$probe" "$subscribe_log"; then received=true; break; fi
  sleep 0.1
done
[[ "$received" == true ]] || fail "The authorized reader did not receive the harmless ACL probe"

printf '%s\n' "Authorization Redis ACL verified: writer is namespace/channel scoped; reader is head-only and read-only; configured subscription receives the probe."
