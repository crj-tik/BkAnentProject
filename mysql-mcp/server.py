"""Authenticated Streamable HTTP MCP server on port 8000."""

import hmac

import anyio
import uvicorn
from mcp.server.fastmcp import FastMCP
from mcp.server.transport_security import TransportSecuritySettings
from mcp.types import ToolAnnotations
from starlette.responses import JSONResponse

from config import Settings
from database import Database


class BearerTokenMiddleware:
    def __init__(self, app, token: str):
        self.app = app
        self.expected = f"Bearer {token}".encode()

    async def __call__(self, scope, receive, send):
        if scope["type"] == "http":
            headers = [value for key, value in scope["headers"] if key.lower() == b"authorization"]
            if len(headers) != 1 or not hmac.compare_digest(headers[0], self.expected):
                response = JSONResponse({"error": "Unauthorized"}, status_code=401,
                    headers={"WWW-Authenticate": "Bearer"})
                await response(scope, receive, send)
                return
        await self.app(scope, receive, send)


def create_app(settings: Settings, database: Database | None = None):
    db = database or Database(settings)
    mcp = FastMCP(
        "bk-mysql-readonly",
        instructions="Read-only access to the configured business databases. Results are bounded.",
        host="0.0.0.0",
        stateless_http=True,
        json_response=True,
        transport_security=TransportSecuritySettings(
            enable_dns_rebinding_protection=True,
            allowed_hosts=list(settings.allowed_hosts),
            allowed_origins=["http://127.0.0.1:*", "http://localhost:*"],
        ),
    )
    annotations = ToolAnnotations(readOnlyHint=True, destructiveHint=False, idempotentHint=True)

    @mcp.tool(annotations=annotations)
    def list_databases() -> list[str]:
        """List databases configured for read-only MCP access."""
        return list(settings.databases)

    @mcp.tool(annotations=annotations)
    def list_tables(database: str) -> dict:
        """List tables and views in an allowed database."""
        return db.list_tables(database)

    @mcp.tool(annotations=annotations)
    def describe_table(database: str, table: str) -> dict:
        """Describe a table's columns, types, keys and comments."""
        return db.describe_table(database, table)

    @mcp.tool(annotations=annotations)
    def query(database: str, sql: str) -> dict:
        """Execute a single read-only MySQL SELECT, including CTE/UNION. Returns columns/rows/truncated."""
        return db.query(database, sql)

    @mcp.custom_route("/healthz", methods=["GET"])
    async def healthz(request):
        try:
            await anyio.to_thread.run_sync(db.ping)
            return JSONResponse({"status": "UP"})
        except Exception:
            return JSONResponse({"status": "DOWN"}, status_code=503)

    return BearerTokenMiddleware(mcp.streamable_http_app(), settings.token)


if __name__ == "__main__":
    uvicorn.run(create_app(Settings.from_env()), host="0.0.0.0", port=8000)
