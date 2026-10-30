#!/usr/bin/env bash
# The same reviewed, checksummed initialization plan drives fresh MySQL and upgrades.
# Never enumerate sql/migrations/*.sql. Secrets stay in the existing mysql process env.
set -euo pipefail
ROOT="${BK_INIT_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
MANIFEST="$ROOT/docker/mysql/init-manifest.tsv"
MODE=upgrade
CONTAINER=""
DRY_RUN=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --mode) [ "$#" -ge 2 ] || { printf '%s\n' '--mode requires fresh|upgrade' >&2; exit 64; }; MODE="$2"; shift 2 ;;
    --container) [ "$#" -ge 2 ] || { printf '%s\n' '--container requires a name' >&2; exit 64; }; CONTAINER="$2"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    *) printf 'Unknown option: %s\n' "$1" >&2; exit 64 ;;
  esac
done
case "$MODE" in fresh|upgrade) ;; *) printf '%s\n' 'Invalid initialization mode' >&2; exit 64 ;; esac
[ -f "$MANIFEST" ] || { printf '%s\n' 'Initialization manifest missing' >&2; exit 66; }
command -v sha256sum >/dev/null || { printf '%s\n' 'sha256sum is required' >&2; exit 69; }
# Validate the entire manifest BEFORE any DDL. Identifiers are a closed reviewed set.
last=0
while IFS=$'\t' read -r order phase version db file checksum check check_checksum extra; do
  check_checksum="${check_checksum%$'\r'}"
  extra="${extra%$'\r'}"
  case "$order" in ''|\#*) continue ;; esac
  [[ "$order" =~ ^[0-9]{3}$ && "$version" =~ ^[a-z0-9-]+$ ]] || exit 65
  [ "$((10#$order))" -gt "$last" ] || { printf '%s\n' 'Manifest order must be unique and increasing' >&2; exit 65; }
  last="$((10#$order))"
  case "$phase:$db" in
    fresh:bk_auth|fresh:bk_interview|\
    upgrade:bk_auth|upgrade:bk_agent|upgrade:bk_rag|upgrade:bk_listing|upgrade:bk_memory|\
    upgrade:bk_interview|upgrade:bk_marketing|upgrade:bk_notification|upgrade:bk_business|\
    upgrade:bk_contract|upgrade:bk_settlement|upgrade:bk_business_review|upgrade:bk_knowledge|upgrade:bk_platform_operations) ;;
    *) exit 65 ;;
  esac
  [[ "$file" =~ ^sql/[a-zA-Z0-9_./-]+\.sql$ && "$check" =~ ^docker/mysql/checks/[a-zA-Z0-9_-]+\.sql$ ]] || exit 65
  [[ "$file" != *..* && "$check" != *..* && "$checksum" =~ ^[a-f0-9]{64}$ && "$check_checksum" =~ ^[a-f0-9]{64}$ && -z "${extra:-}" ]] || exit 65
  # Canonical LF checksums survive Git's Windows/Linux checkout conversion.
  actual="$(tr -d '\r' < "$ROOT/$file" | sha256sum)"; actual="${actual%% *}"
  check_actual="$(tr -d '\r' < "$ROOT/$check" | sha256sum)"; check_actual="${check_actual%% *}"
  [ "$actual" = "$checksum" ] && [ "$check_actual" = "$check_checksum" ] || { printf 'Checksum mismatch: %s\n' "$version" >&2; exit 65; }
done < "$MANIFEST"
if [ "$DRY_RUN" -eq 0 ] && [ -n "$CONTAINER" ]; then
  owner="$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}:{{index .Config.Labels "com.docker.compose.service"}}' "$CONTAINER")"
  [ "$owner" = bk-anent-dev:mysql ] || { printf '%s\n' 'Refusing non-project MySQL container' >&2; exit 64; }
fi
mysql_sql() {
  if [ -n "$CONTAINER" ]; then
    MSYS_NO_PATHCONV=1 docker exec -i "$CONTAINER" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names --default-character-set=utf8mb4'
  else
    MYSQL_PWD="${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD is required}" mysql -uroot --batch --skip-column-names --default-character-set=utf8mb4
  fi
}
retirement_ledger_exists=0
if [ "$DRY_RUN" -eq 0 ]; then
  retirement_ledger_exists="$(printf "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='bk_auth' AND table_name='retired_schema_migration';\n" | mysql_sql)"
fi
while IFS=$'\t' read -r order phase version db file checksum check check_checksum extra; do
  check_checksum="${check_checksum%$'\r'}"
  extra="${extra%$'\r'}"
  case "$order" in ''|\#*) continue ;; esac
  if [ "$MODE" = upgrade ] && [ "$phase" = fresh ]; then continue; fi
  printf '%s %s -> %s [%s]\n' "$order" "$version" "$db" "$phase"
  [ "$DRY_RUN" -eq 0 ] || continue
  # A reviewed retirement preserves exact historical per-schema checksums centrally before dropping a schema.
  # Never silently accept a re-created retired database or an altered historical migration.
  case "$db" in
    bk_contract|bk_settlement|bk_business|bk_business_review|bk_knowledge)
      if [ "$retirement_ledger_exists" = 1 ]; then
        retired="$(printf "SELECT CONCAT(file_sha256, ':', check_sha256, ':', retirement_state) FROM bk_auth.retired_schema_migration WHERE schema_name='%s' AND migration_id='%s';\n" "$db" "$version" | mysql_sql)"
        if [ -n "$retired" ]; then
          case "$retired" in
            "$checksum:$check_checksum:prepared"|"$checksum:$check_checksum:retired") ;;
            *) printf 'Retired migration checksum or state changed: %s\n' "$version" >&2; exit 65 ;;
          esac
          schema_present="$(printf "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='%s';\n" "$db" | mysql_sql)"
          if [ "$schema_present" = 0 ]; then
            printf 'Verified retired migration %s\n' "$version"
            continue
          fi
          [ "${retired##*:}" = prepared ] || { printf 'Retired schema unexpectedly exists: %s\n' "$db" >&2; exit 65; }
        fi
      fi
      ;;
  esac
  # These literal identifiers come only from the closed, validated manifest above.
  exists="$(printf "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='%s' AND table_name='bk_schema_migration';\n" "$db" | mysql_sql)"
  if [ "$exists" = 1 ]; then
    previous="$(printf "SELECT CONCAT(file_sha256, ':', check_sha256) FROM %s.bk_schema_migration WHERE migration_id='%s';\n" "$db" "$version" | mysql_sql)"
    [ -z "$previous" ] || [ "$previous" = "$checksum:$check_checksum" ] || { printf 'Applied migration checksum changed: %s\n' "$version" >&2; exit 65; }
  fi
  # A later migration may deliberately supersede an earlier migration's postcondition.
  # Once the exact files are recorded, do not replay them or re-check that historical state.
  if [ -n "${previous:-}" ]; then
    printf 'Verified previously applied %s\n' "$version"
    unset previous
    continue
  fi
  # Fresh-only files never replay historical platform seed rows on an existing volume.
  if [ "$phase" = upgrade ] || [ -z "${previous:-}" ]; then
    # rag_control creates its own new database; all other targets must already exist.
    if [ "$db" = bk_rag ] || [ "$phase" = fresh ]; then
      mysql_sql < "$ROOT/$file" >/dev/null
    else
      { printf 'USE `%s`;\n' "$db"; while IFS= read -r line || [ -n "$line" ]; do printf '%s\n' "$line"; done < "$ROOT/$file"; } | mysql_sql >/dev/null
    fi
  fi
  # Each mysql_sql call opens a new connection; select the same target database
  # for schema checks that the migration was applied to.
  result="$( { printf 'USE `%s`;\n' "$db"; cat "$ROOT/$check"; } | mysql_sql )"
  [ "$result" = 1 ] || { printf 'Schema verification failed: %s\n' "$version" >&2; exit 65; }
  printf "CREATE TABLE IF NOT EXISTS %s.bk_schema_migration (migration_id VARCHAR(128) PRIMARY KEY, file_sha256 CHAR(64) NOT NULL, check_sha256 CHAR(64) NOT NULL, applied_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)); INSERT INTO %s.bk_schema_migration(migration_id,file_sha256,check_sha256) VALUES ('%s','%s','%s') ON DUPLICATE KEY UPDATE migration_id=migration_id;\n" "$db" "$db" "$version" "$checksum" "$check_checksum" | mysql_sql >/dev/null
  unset previous
  printf 'Verified %s\n' "$version"
done < "$MANIFEST"
