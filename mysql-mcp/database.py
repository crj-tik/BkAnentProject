"""Bounded read-only queries; MySQL grants are the final authorization boundary."""

import base64
from contextlib import closing
from datetime import date, datetime, time
from decimal import Decimal

import mysql.connector
import sqlglot
from sqlglot import exp

from config import Settings


def prepare_query(sql: str, max_rows: int) -> str:
    if len(sql) > 20000:
        raise ValueError("SQL exceeds the 20000-character limit")
    statements = sqlglot.parse(sql, read="mysql")
    if len(statements) != 1 or not isinstance(statements[0], (exp.Select, exp.Union)):
        raise ValueError("Only a single SELECT query (including CTE/UNION) is allowed")
    query = statements[0]
    forbidden_nodes = (exp.DML, exp.DDL, exp.Into, exp.Lock, exp.Command, exp.Hint)
    for node in query.walk():
        if isinstance(node, forbidden_nodes):
            raise ValueError("Write operations, file output, locking reads and optimizer hints are forbidden")
        if isinstance(node, exp.Func):
            name = node.name.upper() if isinstance(node, exp.Anonymous) else node.sql_name()
            if name in {"SLEEP", "BENCHMARK", "GET_LOCK", "RELEASE_LOCK", "RELEASE_ALL_LOCKS", "LOAD_FILE"}:
                raise ValueError(f"Function {name} is forbidden")
    # Serialize the parsed statement instead of forwarding raw SQL, removing
    # executable MySQL comments and preventing multiple-statement execution.
    limit = query.args.get("limit")
    requested = max_rows + 1
    if limit:
        value = limit.expression
        if not isinstance(value, exp.Literal) or not value.is_int or int(value.this) < 0:
            raise ValueError("LIMIT must be a non-negative integer")
        requested = min(requested, int(value.this))
    return query.limit(requested).sql(dialect="mysql", comments=False)


def json_value(value):
    if isinstance(value, (datetime, date, time)):
        return value.isoformat()
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, bytes):
        return {"base64": base64.b64encode(value).decode("ascii")}
    return value


class Database:
    def __init__(self, settings: Settings):
        self.settings = settings

    def connect(self, database: str):
        if database not in self.settings.databases:
            raise ValueError("Database is not in MYSQL_MCP_DATABASES")
        return mysql.connector.connect(
            host=self.settings.host,
            port=self.settings.port,
            user=self.settings.user,
            password=self.settings.password,
            database=database,
            connection_timeout=3,
            read_timeout=self.settings.query_timeout_ms // 1000 + 3,
            write_timeout=3,
            use_pure=True,
        )

    def read(self, database: str, sql: str, params=None) -> dict:
        with closing(self.connect(database)) as connection:
            try:
                with closing(connection.cursor()) as cursor:
                    cursor.execute("SET SESSION MAX_EXECUTION_TIME = %s", (self.settings.query_timeout_ms,))
                    connection.start_transaction(readonly=True)
                    cursor.execute(sql, params)
                    rows = cursor.fetchmany(self.settings.max_rows + 1)
                    columns = list(cursor.column_names)
                    # Drain only bounded query results; close the connection on
                    # oversized metadata results without fetching the remainder.
                    truncated = len(rows) > self.settings.max_rows
                    return {
                        "columns": columns,
                        "rows": [[json_value(value) for value in row] for row in rows[:self.settings.max_rows]],
                        "truncated": truncated,
                    }
            finally:
                # Closing the connection also rolls back if an unread metadata
                # result prevents an explicit rollback.
                try:
                    connection.rollback()
                except mysql.connector.Error:
                    pass

    def query(self, database: str, sql: str) -> dict:
        return self.read(database, prepare_query(sql, self.settings.max_rows))

    def list_tables(self, database: str) -> dict:
        return self.read(database,
            "SELECT TABLE_NAME, TABLE_TYPE, TABLE_COMMENT FROM information_schema.TABLES "
            "WHERE TABLE_SCHEMA = %s ORDER BY TABLE_NAME LIMIT %s",
            (database, self.settings.max_rows + 1))

    def describe_table(self, database: str, table: str) -> dict:
        return self.read(database,
            "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY, COLUMN_DEFAULT, COLUMN_COMMENT "
            "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = %s AND TABLE_NAME = %s "
            "ORDER BY ORDINAL_POSITION LIMIT %s", (database, table, self.settings.max_rows + 1))

    def ping(self):
        self.query(self.settings.databases[0], "SELECT 1")
