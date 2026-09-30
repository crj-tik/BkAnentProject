import os
import sys
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import sqlglot
from starlette.testclient import TestClient

from config import Settings
from database import Database, prepare_query
from init_account import provision
from server import create_app


SETTINGS = Settings("mysql", 3306, "mcp_ro", "test-password", ("bk_listing",),
    "test-token-with-at-least-32-bytes-long", 2, 5000, ("testserver",))


class QueryPolicyTest(unittest.TestCase):
    def test_writes_multiple_statements_and_side_effects_are_rejected(self):
        for sql in (
            "DELETE FROM listing_info", "UPDATE listing_info SET id=1", "DROP TABLE listing_info",
            "SELECT 1; DELETE FROM listing_info", "SELECT * FROM listing_info FOR UPDATE",
            "SELECT * FROM listing_info INTO OUTFILE '/tmp/export'", "SELECT SLEEP(100)",
            "SELECT GET_LOCK('mcp', 100)", "SELECT LOAD_FILE('/etc/passwd')",
            "SELECT /*+ MAX_EXECUTION_TIME(0) */ * FROM listing_info", "SELECT 1 LIMIT -1",
        ):
            with self.subTest(sql=sql), self.assertRaises((ValueError, sqlglot.errors.ParseError)):
                prepare_query(sql, 2)

    def test_select_cte_union_are_bounded_and_smaller_limit_is_preserved(self):
        for sql in (
            "SELECT * FROM listing_info", "SELECT * FROM listing_info LIMIT 10000",
            "WITH x AS (SELECT 1 AS id) SELECT * FROM x", "SELECT 1 UNION SELECT 2",
        ):
            with self.subTest(sql=sql):
                parsed = sqlglot.parse_one(prepare_query(sql, 2), read="mysql")
                self.assertEqual(int(parsed.args["limit"].expression.this), 3)
        self.assertTrue(prepare_query("SELECT 1 LIMIT 1", 2).endswith("LIMIT 1"))

    def test_executable_comments_are_not_forwarded(self):
        self.assertNotIn("INTO", prepare_query("SELECT 1 /*!50000 INTO OUTFILE '/tmp/export' */", 2))

    def test_database_outside_allowlist_never_connects(self):
        with patch("database.mysql.connector.connect") as connect:
            with self.assertRaises(ValueError):
                Database(SETTINGS).query("mysql", "SELECT 1")
            connect.assert_not_called()

    def test_connection_is_read_only_and_results_are_truncated(self):
        connection = MagicMock()
        cursor = connection.cursor.return_value
        cursor.column_names = ("id",)
        cursor.fetchmany.return_value = [(1,), (2,), (3,)]
        with patch("database.mysql.connector.connect", return_value=connection):
            result = Database(SETTINGS).query("bk_listing", "SELECT id FROM listing_info")
        connection.start_transaction.assert_called_once_with(readonly=True)
        self.assertEqual(result, {"columns": ["id"], "rows": [[1], [2]], "truncated": True})
        connection.rollback.assert_called_once()
        connection.close.assert_called_once()


class ServerProtocolTest(unittest.TestCase):
    def setUp(self):
        self.database = Database(SETTINGS)
        self.database.ping = MagicMock()
        self.database.read = MagicMock(return_value={"columns": ["id"], "rows": [[1]], "truncated": False})
        self.client = TestClient(create_app(SETTINGS, self.database))
        self.client.__enter__()
        self.headers = {"Authorization": "Bearer " + SETTINGS.token,
            "Accept": "application/json, text/event-stream"}

    def tearDown(self):
        self.client.__exit__(None, None, None)

    def rpc(self, method, params=None):
        response = self.client.post("/mcp", headers=self.headers,
            json={"jsonrpc": "2.0", "id": 1, "method": method, "params": params or {}})
        self.assertEqual(response.status_code, 200, response.text)
        return response.json()

    def test_missing_wrong_and_duplicate_bearer_headers_are_rejected(self):
        for path in ("/mcp", "/healthz", "/mcp/", "/missing"):
            for headers in ({}, {"Authorization": "Bearer incorrect"},
                [("Authorization", "Bearer " + SETTINGS.token), ("Authorization", "Bearer bad")]):
                with self.subTest(path=path, headers=headers):
                    self.assertEqual(self.client.get(path, headers=headers).status_code, 401)

    def test_initialize_list_and_call_tools(self):
        initialized = self.rpc("initialize", {"protocolVersion": "2025-03-26", "capabilities": {},
            "clientInfo": {"name": "smoke-test", "version": "1.0"}})
        self.assertEqual(initialized["result"]["serverInfo"]["name"], "bk-mysql-readonly")
        names = {tool["name"] for tool in self.rpc("tools/list")["result"]["tools"]}
        self.assertEqual(names, {"list_databases", "list_tables", "describe_table", "query"})
        result = self.rpc("tools/call", {"name": "query", "arguments": {
            "database": "bk_listing", "sql": "SELECT 1"}})
        self.assertFalse(result["result"].get("isError", False))
        self.database.read.assert_called_once_with("bk_listing", "SELECT 1 LIMIT 3")

    def test_write_query_returns_mcp_error_without_database_access(self):
        result = self.rpc("tools/call", {"name": "query", "arguments": {
            "database": "bk_listing", "sql": "DELETE FROM listing_info"}})
        self.assertTrue(result["result"]["isError"])
        self.database.read.assert_not_called()

    def test_health_checks_database_and_reports_failure(self):
        self.assertEqual(self.client.get("/healthz", headers=self.headers).status_code, 200)
        self.database.ping.side_effect = RuntimeError("private connection detail")
        response = self.client.get("/healthz", headers=self.headers)
        self.assertEqual(response.status_code, 503)
        self.assertEqual(response.json(), {"status": "DOWN"})

    def test_unapproved_host_is_rejected(self):
        self.assertEqual(self.client.post("/mcp", headers={**self.headers, "Host": "evil.example"},
            json={"jsonrpc": "2.0", "id": 1, "method": "tools/list"}).status_code, 421)


class ProvisioningTest(unittest.TestCase):
    def test_existing_account_is_reset_to_exact_read_only_grants(self):
        for partial_revokes in (0, 1):
            connection = MagicMock()
            cursor = connection.cursor.return_value
            cursor.fetchone.side_effect = [("bk_listing",), (partial_revokes,)]
            provision(connection, ("bk_listing",), "password-with-'quote")
            statements = [call.args[0] for call in cursor.execute.call_args_list]
            self.assertIn("REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'mcp_ro'@'%'", statements)
            name = "bk_listing" if partial_revokes else r"bk\_listing"
            self.assertEqual(statements[-1], f"GRANT SELECT, SHOW VIEW ON `{name}`.* TO 'mcp_ro'@'%'")
            self.assertNotIn("password-with-'quote", "\n".join(statements))

    def test_missing_schema_does_not_modify_account(self):
        connection = MagicMock()
        connection.cursor.return_value.fetchone.return_value = None
        with self.assertRaises(ValueError):
            provision(connection, ("missing_db",), "test-password")
        self.assertEqual(connection.cursor.return_value.execute.call_count, 1)

    def test_startup_rejects_missing_credentials_root_and_system_databases(self):
        env = {"MYSQL_PASSWORD": "test", "MYSQL_MCP_TOKEN": SETTINGS.token,
            "MYSQL_MCP_DATABASES": "bk_listing"}
        for override in ({"MYSQL_PASSWORD": ""}, {"MYSQL_MCP_TOKEN": ""},
            {"MYSQL_USER": "root"}, {"MYSQL_MCP_DATABASES": "mysql"},
            {"MYSQL_MCP_DATABASES": "bk_listing`; DROP DATABASE x"}):
            with self.subTest(override=override), patch.dict(os.environ, {**env, **override}, clear=True):
                with self.assertRaises(ValueError):
                    Settings.from_env()


if __name__ == "__main__":
    unittest.main()
