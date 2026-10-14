#!/usr/bin/env bash
# Apply only the reviewed versioned plan shared with fresh Compose initialization.
# --dry-run validates all SHA256 checksums and lists selected steps without connecting.
# No directory enumeration, dotenv parsing, platform seeds, destructive operations or secrets in argv.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
args=(--mode upgrade --container bk-anent-dev-mysql-1)
while [ "$#" -gt 0 ]; do
  case "$1" in
    --dry-run) args+=(--dry-run); shift ;;
    --yes) shift ;;
    --container) [ "$#" -ge 2 ] || { printf '%s\n' '--container requires a name' >&2; exit 64; }; args+=(--container "$2"); shift 2 ;;
    -h|--help) printf '%s\n' 'apply-migrations.sh [--dry-run] [--yes] [--container NAME]' 'Only docker/mysql/init-manifest.tsv upgrade entries are eligible.'; exit 0 ;;
    *) printf 'Unsupported option: %s (use the versioned manifest, not arbitrary --file)\n' "$1" >&2; exit 64 ;;
  esac
done
exec bash "$SCRIPT_DIR/run-init-manifest.sh" "${args[@]}"
