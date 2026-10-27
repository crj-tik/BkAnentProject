#!/usr/bin/env python3
"""Count credential-shaped values in textual columns without returning row data."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass


DATABASE_NAME = re.compile(r"[A-Za-z0-9_]+")
MAX_QUERY_TIME_MS = 10000
TEXT_COLUMN_QUERY = """
SELECT table_name, column_name
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND LOWER(data_type) IN ('char', 'varchar', 'tinytext', 'text', 'mediumtext', 'longtext', 'json')
  AND NOT (table_name = 'interview_participant_ticket' AND column_name = 'ticket_hash')
  AND LOWER(column_name) NOT REGEXP '(^|_)(hash|digest|checksum)(_|$)'
ORDER BY table_name, ordinal_position;
""".strip()

CREDENTIAL_PATTERN = (
    r"(?i)(Bearer[[:space:]]+[A-Za-z0-9._~+/=-]{12,}"
    r"|eyJ[A-Za-z0-9_-]{8,}[.][A-Za-z0-9_-]{8,}[.][A-Za-z0-9_-]{8,}"
    r"|(access[_-]?token|refresh[_-]?token|session[_-]?ticket|participant[_-]?ticket|"
    r"ticket|authorization|authz|request[_-]?proof|proof|credential)[[:space:]]*[:=]"
    r"[[:space:]]*[^[:space:],;]+"
    r"|(^|[^A-Za-z0-9_-])[A-Za-z0-9_-]{40,128}([^A-Za-z0-9_-]|$))"
)


@dataclass(frozen=True)
class TextColumn:
    table: str
    column: str


class ScanError(Exception):
    pass


def quote_identifier(value: str) -> str:
    return "`" + value.replace("`", "``") + "`"


def mysql_args(mysql: str, database: str, defaults_extra_file: str | None) -> list[str]:
    args = [mysql]
    # MySQL requires defaults-extra-file to appear before other client options.
    if defaults_extra_file:
        args.append(f"--defaults-extra-file={defaults_extra_file}")
    args.extend(["--batch", "--raw", "--skip-column-names", "--connect-timeout=10", f"--database={database}"])
    return args


def invoke_mysql(args: list[str], sql: str) -> list[str]:
    try:
        completed = subprocess.run(args, input=sql, text=True, capture_output=True, check=False)
    except OSError as error:
        if isinstance(error, FileNotFoundError):
            raise ScanError("mysql client could not be started") from None
        raise ScanError("mysql client could not be started") from None
    if completed.returncode != 0:
        # Do not forward client diagnostics: they can contain connection details.
        raise ScanError(f"mysql client failed with exit code {completed.returncode}; details suppressed")
    return [line for line in completed.stdout.splitlines() if line.strip()]


def parse_columns(lines: list[str]) -> list[TextColumn]:
    columns: list[TextColumn] = []
    for line in lines:
        fields = line.split("\t")
        if len(fields) != 2 or not all(fields):
            raise ScanError("mysql returned an invalid metadata row; details suppressed")
        columns.append(TextColumn(fields[0], fields[1]))
    return columns


def count_queries(database: str, columns: list[TextColumn]) -> str:
    schema = quote_identifier(database)
    statements = []
    for item in columns:
        table = quote_identifier(item.table)
        column = quote_identifier(item.column)
        statements.append(
            f"SELECT /*+ MAX_EXECUTION_TIME({MAX_QUERY_TIME_MS}) */ COUNT(*) FROM " + schema + "." + table
            + " WHERE CAST(" + column + " AS CHAR CHARACTER SET utf8mb4) REGEXP '"
            + CREDENTIAL_PATTERN + "';"
        )
    return "\n".join(statements)


def scan(database: str, mysql: str = "mysql", defaults_extra_file: str | None = None) -> list[tuple[TextColumn, int]]:
    if not DATABASE_NAME.fullmatch(database):
        raise ScanError("database must contain only ASCII letters, digits, and underscores")
    args = mysql_args(mysql, database, defaults_extra_file)
    columns = parse_columns(invoke_mysql(args, TEXT_COLUMN_QUERY))
    if not columns:
        raise ScanError("no readable text or JSON columns were found; no scan was performed")
    counts = invoke_mysql(args, count_queries(database, columns))
    if len(counts) != len(columns) or any(not line.isdecimal() for line in counts):
        raise ScanError("mysql returned an invalid count result; details suppressed")
    return [(column, int(count)) for column, count in zip(columns, counts, strict=True)]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", required=True, help="selected interview database, for example bk_interview")
    parser.add_argument("--mysql", default="mysql", help="mysql client executable")
    parser.add_argument("--defaults-extra-file", help="protected MySQL option file; credentials are never printed")
    options = parser.parse_args(argv)
    try:
        results = scan(options.database, options.mysql, options.defaults_extra_file)
    except ScanError as error:
        print(f"scan failed: {error}", file=sys.stderr)
        return 2

    print(f"database={options.database} scanned_columns={len(results)}")
    for column, count in results:
        print(f"{column.table}.{column.column}\t{count}")
    print(f"matched_column_rows={sum(count for _, count in results)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
