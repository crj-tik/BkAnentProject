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
    ("a2a-task-authorization-interview-20261026-v1", "bk_interview", "sql/migrations/20261026_a2a_task_authorization.sql"),
    ("a2a-task-authorization-listing-20261026-v1", "bk_listing", "sql/migrations/20261026_a2a_task_authorization.sql"),
    ("a2a-task-authorization-marketing-20261026-v1", "bk_marketing", "sql/migrations/20261026_a2a_task_authorization.sql"),
    ("a2a-task-authorization-notification-20261026-v1", "bk_notification", "sql/migrations/20261026_a2a_task_authorization.sql"),
    ("a2a-task-authorization-business-20261026-v1", "bk_business", "sql/migrations/20261026_a2a_task_authorization.sql"),
    ("a2a-task-authorization-contract-20261026-v1", "bk_contract", "sql/migrations/20261026_a2a_task_authorization.sql"),
    ("a2a-task-authorization-settlement-20261026-v1", "bk_settlement", "sql/migrations/20261026_a2a_task_authorization.sql"),
}

EXPECTED_SUPPORT_MIGRATIONS = {
    ("interview-director-command-20260930-v1", "bk_interview", "sql/migrations/20260930_interview_director_command.sql"),
}

EXPECTED_KNOWLEDGE_MIGRATIONS = {
    ("knowledge-database-bootstrap-20261027-v1", "bk_auth", "sql/migrations/20261027_knowledge_database_bootstrap.sql"),
    ("knowledge-domain-20261027-v1", "bk_knowledge", "sql/migrations/20261027_knowledge_domain.sql"),
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
    "sql/migrations/20261027_knowledge_domain.sql": (
        "knowledge_case",
        "knowledge_revision",
        "knowledge_source_ref",
        "knowledge_review_decision",
        "knowledge_operation_receipt",
        "knowledge_generation_task",
    ),
}


def canonical_hash(path: Path) -> str:
    return hashlib.sha256(path.read_bytes().replace(b"\r", b"")).hexdigest()


def fail(message: str) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(1)


def main() -> None:
    runner = RUNNER.read_text(encoding="utf-8")
    target_case = re.search(r'case "\$phase:\$db" in(.*?)\n  esac', runner, re.S)
    if not target_case:
        fail("deployment runner target allowlist was not found")
    allowed = set(re.findall(r"(?:fresh|upgrade):bk_[a-z0-9_]+", target_case.group(1)))

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
    missing_knowledge = EXPECTED_KNOWLEDGE_MIGRATIONS - observed
    if missing_knowledge:
        fail(f"required Knowledge migrations are missing: {sorted(missing_knowledge)}")

    for relative, tables in MIGRATION_TABLES.items():
        migration = (ROOT / relative).read_text(encoding="utf-8")
        if re.search(r"(?im)^\s*(DROP TABLE|TRUNCATE|DELETE FROM)\b", migration):
            fail(f"new authorization migration must remain additive: {relative}")
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
