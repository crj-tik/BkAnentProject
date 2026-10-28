#!/usr/bin/env python3
"""Static contract checks for the reviewed MySQL migration manifest."""

from __future__ import annotations

import hashlib
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "docker/mysql/init-manifest.tsv"
RUNNER = ROOT / "scripts/deploy/run-init-manifest.sh"

EXPECTED_AUTHZ_MIGRATIONS = {
    ("auth-execution-binding-20261026-v1", "bk_auth", "sql/migrations/20261026_authorization_execution_binding.sql"),
    ("baseline-permission-retirement-20261028-v1", "bk_auth", "sql/migrations/20261028_baseline_permission_retirement.sql"),
    ("a2a-task-authorization-interview-20261026-v1", "bk_interview", "sql/migrations/20261026_a2a_task_authorization.sql"),
    ("a2a-task-authorization-listing-20261026-v1", "bk_listing", "sql/migrations/20261026_a2a_task_authorization.sql"),
    ("a2a-task-authorization-marketing-20261026-v1", "bk_marketing", "sql/migrations/20261026_a2a_task_authorization.sql"),
    ("a2a-task-authorization-notification-20261026-v1", "bk_notification", "sql/migrations/20261026_a2a_task_authorization.sql"),
    ("a2a-task-authorization-business-20261026-v1", "bk_business", "sql/migrations/20261026_a2a_task_authorization.sql"),
    ("a2a-task-authorization-contract-20261026-v1", "bk_contract", "sql/migrations/20261026_a2a_task_authorization.sql"),
    ("a2a-task-authorization-settlement-20261026-v1", "bk_settlement", "sql/migrations/20261026_a2a_task_authorization.sql"),
}

BASELINE_PERMISSION_CODES = {
    "agent.chat.use", "agent.workflow.read", "agent.workflow.artifact.read",
    "listing.read", "listing.create", "listing.update.own",
    "customer.read", "customer.create", "customer.update.own",
    "customer.follow.read.own", "customer.follow.write.own",
    "customer.favorite.read.own", "customer.favorite.write.own", "customer.match.own",
    "compare.report.create", "compare.report.read.own", "media.task.read.own",
    "notification.message.read.own", "notification.message.mark-read.own",
    "interview.initiate", "interview.read", "interview.respond", "interview.end",
    "memory.read", "memory.write", "memory.search",
}

BASELINE_RETIREMENT_MIGRATION = "sql/migrations/20261028_baseline_permission_retirement.sql"

EXPECTED_SUPPORT_MIGRATIONS = {
    ("interview-director-command-20260930-v1", "bk_interview", "sql/migrations/20260930_interview_director_command.sql"),
}

MIGRATION_TABLES = {
    "sql/migrations/20260930_interview_director_command.sql": (
        "interview_director_command",
    ),
    "sql/migrations/20261026_authorization_execution_binding.sql": (
        "auth_authorization_execution_binding",
    ),
    "sql/migrations/20261026_a2a_task_authorization.sql": (
        "a2a_request_nonce",
        "a2a_task_owner",
        "a2a_task_execution_reservation",
    ),
}


def canonical_hash(path: Path) -> str:
    return hashlib.sha256(path.read_bytes().replace(b"\r", b"")).hexdigest()


def fail(message: str) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(1)


def permission_code_lists(content: str) -> list[list[str]]:
    return [re.findall(r"'([^']+)'", match.group(1)) for match in
            re.finditer(r"(?is)BINARY\s+permission_code\s+IN\s*\((.*?)\)", content)]


def main() -> None:
    runner = RUNNER.read_text(encoding="utf-8")
    target_case = re.search(r'case "\$phase:\$db" in(.*?)\n  esac', runner, re.S)
    if not target_case:
        fail("deployment runner target allowlist was not found")
    allowed = set(re.findall(r"(?:fresh|upgrade):bk_[a-z0-9_]+", target_case.group(1)))

    applied_migration_branch = re.search(
        r'if \[ -n "\$\{previous:-\}" \]; then(.*?)\n  fi', runner, re.S
    )
    if not applied_migration_branch or "continue" not in applied_migration_branch.group(1):
        fail("deployment runner must skip already-applied migrations after checksum verification")
    if runner.find(applied_migration_branch.group(0)) > runner.find("mysql_sql < \"$ROOT/$file\""):
        fail("already-applied migration entries must be skipped before SQL replay")

    rows: list[tuple[int, str, str, str, str, str, str, str]] = []
    for line_number, line in enumerate(MANIFEST.read_text(encoding="utf-8").splitlines(), start=1):
        if not line or line.startswith("#"):
            continue
        columns = line.split("\t")
        if len(columns) != 8:
            fail(f"manifest line {line_number} must contain 8 tab-separated columns")
        order, phase, version, database, sql_path, sql_hash, check_path, check_hash = columns
        if not re.fullmatch(r"\d{3}", order) or not re.fullmatch(r"[a-z0-9-]+", version):
            fail(f"manifest line {line_number} has an invalid order or version")
        if f"{phase}:{database}" not in allowed:
            fail(f"manifest line {line_number} target {phase}:{database} is rejected by the runner")
        for relative, expected in ((sql_path, sql_hash), (check_path, check_hash)):
            path = (ROOT / relative).resolve()
            if ROOT not in path.parents or not path.is_file():
                fail(f"manifest line {line_number} references an invalid file: {relative}")
            if canonical_hash(path) != expected:
                fail(f"manifest line {line_number} checksum mismatch: {relative}")
        rows.append((int(order), phase, version, database, sql_path, sql_hash, check_path, check_hash))

    orders = [row[0] for row in rows]
    if orders != sorted(set(orders)):
        fail("manifest order values must be unique and increasing")
    version_keys = [(row[3], row[2]) for row in rows]
    if len(version_keys) != len(set(version_keys)):
        fail("migration versions must be unique within each target database")

    observed = {(row[2], row[3], row[4]) for row in rows}
    missing = EXPECTED_AUTHZ_MIGRATIONS - observed
    if missing:
        fail(f"required auth/A2A migration entries are missing: {sorted(missing)}")
    missing_support = EXPECTED_SUPPORT_MIGRATIONS - observed
    if missing_support:
        fail(f"required upgrade compatibility migrations are missing: {sorted(missing_support)}")

    baseline_rows = [row for row in rows if row[4] == BASELINE_RETIREMENT_MIGRATION]
    if len(baseline_rows) != 1:
        fail("baseline permission retirement must be registered exactly once")
    baseline_migration = (ROOT / BASELINE_RETIREMENT_MIGRATION).read_text(encoding="utf-8")
    baseline_check = (ROOT / baseline_rows[0][6]).read_text(encoding="utf-8")
    lock_position = baseline_migration.find("FOR UPDATE")
    marker_position = baseline_migration.find("SET @baseline_permission_retirement")
    if lock_position < 0 or marker_position <= lock_position:
        fail("baseline retirement must re-check its idempotency marker after acquiring the writer lock")
    for label, source in (("migration", baseline_migration), ("schema check", baseline_check)):
        lists = permission_code_lists(source)
        if len(lists) != 2 or any(set(codes) != BASELINE_PERMISSION_CODES
                                  or len(codes) != len(BASELINE_PERMISSION_CODES) for codes in lists):
            fail(f"baseline permission operation lists differ in the {label}")

    migration_paths = {row[4] for row in rows}
    for relative in migration_paths:
        migration = (ROOT / relative).read_text(encoding="utf-8")
        if re.search(r"(?im)^\s*(DROP TABLE|TRUNCATE)\b", migration):
            fail(f"migration must not drop or truncate data: {relative}")
        delete_statements = re.findall(r"(?ims)^\s*DELETE\s+FROM\s+([a-z0-9_]+)(.*?);", migration)
        if delete_statements:
            if relative != BASELINE_RETIREMENT_MIGRATION or len(delete_statements) != 1:
                fail(f"unreviewed destructive data migration: {relative}")
            table, predicate = delete_statements[0]
            code_list = re.search(r"(?is)BINARY\s+permission_code\s+IN\s*\((.*?)\)", predicate)
            codes = re.findall(r"'([^']+)'", code_list.group(1)) if code_list else []
            if table != "auth_role_permission" or "@baseline_permission_retirement" not in predicate:
                fail("baseline retirement must be guarded and limited to auth_role_permission")
            if set(codes) != BASELINE_PERMISSION_CODES or len(codes) != len(BASELINE_PERMISSION_CODES):
                fail("baseline retirement delete list differs from the reviewed exact operation set")

    for relative, tables in MIGRATION_TABLES.items():
        migration = (ROOT / relative).read_text(encoding="utf-8")
        for table in tables:
            if not re.search(rf"(?i)CREATE TABLE IF NOT EXISTS\s+{re.escape(table)}\b", migration):
                fail(f"idempotent table creation missing for {table} in {relative}")

    for check_path in sorted({row[6] for row in rows}):
        check = (ROOT / check_path).read_text(encoding="utf-8")
        if len(re.findall(r"(?m)^SELECT\b", check)) != 1:
            fail(f"schema check must return exactly one scalar result: {check_path}")

    print(f"OK: {len(rows)} manifest entries, all targets and canonical SHA-256 values verified")


if __name__ == "__main__":
    main()
