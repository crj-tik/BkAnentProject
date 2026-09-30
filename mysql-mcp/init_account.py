"""Idempotently manage the dedicated account without touching business data."""

import os
from contextlib import closing

import mysql.connector

from config import allowed_databases, required


def provision(connection, databases: tuple[str, ...], password: str):
    with closing(connection.cursor()) as cursor:
        # Verify all schemas before changing any grants or passwords.
        for database in databases:
            cursor.execute("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = %s", (database,))
            if cursor.fetchone() is None:
                raise ValueError(f"Database {database} does not exist; initialize it before enabling MCP")
        cursor.execute("CREATE USER IF NOT EXISTS 'mcp_ro'@'%' IDENTIFIED BY %s", (password,))
        cursor.execute("ALTER USER 'mcp_ro'@'%' IDENTIFIED BY %s WITH MAX_USER_CONNECTIONS 10", (password,))
        cursor.execute("REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'mcp_ro'@'%'")
        # MySQL treats _ and % as grant wildcards unless partial_revokes is ON.
        cursor.execute("SELECT @@GLOBAL.partial_revokes")
        partial_revokes = bool(cursor.fetchone()[0])
        for database in databases:
            grant_name = database if partial_revokes else database.replace("_", r"\_")
            cursor.execute(f"GRANT SELECT, SHOW VIEW ON `{grant_name}`.* TO 'mcp_ro'@'%'")


def main():
    databases = allowed_databases()
    password = required("MYSQL_MCP_PASSWORD")
    with closing(mysql.connector.connect(
        host=os.environ.get("MYSQL_HOST", "mysql"),
        port=int(os.environ.get("MYSQL_PORT", "3306")),
        user="root",
        password=required("MYSQL_PASSWORD"),
        connection_timeout=5,
        use_pure=True,
    )) as connection:
        provision(connection, databases, password)
    print("Read-only mcp_ro account is ready")


if __name__ == "__main__":
    main()
