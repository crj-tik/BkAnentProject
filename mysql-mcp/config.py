"""Shared configuration for the MCP server and account provisioning job."""

import os
import re
from dataclasses import dataclass


def required(name: str) -> str:
    value = os.environ.get(name, "")
    if not value.strip():
        raise ValueError(f"{name} must be set")
    return value


def allowed_databases() -> tuple[str, ...]:
    names = tuple(dict.fromkeys(required("MYSQL_MCP_DATABASES").split(",")))
    if any(not re.fullmatch(r"[a-zA-Z][a-zA-Z0-9_]{0,63}", name) for name in names):
        raise ValueError("MYSQL_MCP_DATABASES must contain comma-separated database names")
    if any(name.lower() in {"mysql", "sys", "information_schema", "performance_schema"} for name in names):
        raise ValueError("System databases cannot be exposed through MCP")
    return names


def bounded_integer(name: str, default: int, minimum: int, maximum: int) -> int:
    value = int(os.environ.get(name, default))
    if not minimum <= value <= maximum:
        raise ValueError(f"{name} must be between {minimum} and {maximum}")
    return value


@dataclass(frozen=True)
class Settings:
    host: str
    port: int
    user: str
    password: str
    databases: tuple[str, ...]
    token: str
    max_rows: int
    query_timeout_ms: int
    allowed_hosts: tuple[str, ...]

    @classmethod
    def from_env(cls) -> "Settings":
        token = required("MYSQL_MCP_TOKEN")
        if len(token.encode()) < 32:
            raise ValueError("MYSQL_MCP_TOKEN must be at least 32 bytes")
        user = os.environ.get("MYSQL_USER", "mcp_ro")
        if user != "mcp_ro":
            raise ValueError("The MCP server must use the dedicated mcp_ro account")
        return cls(
            host=os.environ.get("MYSQL_HOST", "mysql"),
            port=bounded_integer("MYSQL_PORT", 3306, 1, 65535),
            user=user,
            password=required("MYSQL_PASSWORD"),
            databases=allowed_databases(),
            token=token,
            max_rows=bounded_integer("MYSQL_MCP_MAX_ROWS", 200, 1, 1000),
            query_timeout_ms=bounded_integer("MYSQL_MCP_QUERY_TIMEOUT_MS", 5000, 100, 60000),
            allowed_hosts=tuple(os.environ.get(
                "MYSQL_MCP_ALLOWED_HOSTS", "127.0.0.1:*,localhost:*,mysql-mcp:8000"
            ).split(",")),
        )
