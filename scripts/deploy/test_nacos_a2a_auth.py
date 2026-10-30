#!/usr/bin/env python3
"""Unit checks for the local Nacos A2A account bootstrap."""

from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import os
import urllib.parse
import urllib.request
import unittest
from pathlib import Path
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "init_nacos_a2a_auth", Path(__file__).with_name("init-nacos-a2a-auth.py"))
init_auth = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(init_auth)


class FakeResponse:
    def __init__(self, payload: dict[str, object]):
        self.payload = json.dumps(payload).encode()
        self.status = 200

    def read(self):
        return self.payload

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False


class FakeOpener:
    def __init__(self, account_exists: bool = False):
        self.account_exists = account_exists
        self.calls: list[tuple[str, str, dict[str, list[str]]]] = []

    def open(self, request: urllib.request.Request, timeout: int):
        url = urllib.parse.urlsplit(request.full_url)
        values = urllib.parse.parse_qs(
            (request.data or url.query.encode()).decode(), keep_blank_values=True)
        self.calls.append((request.method, url.path, values))
        if url.path.endswith("/v3/auth/user/login"):
            username = values.get("username", [""])[0]
            token = "admin-token" if username == "nacos" else "service-token"
            return FakeResponse({"code": 0, "data": {"accessToken": token}})
        if url.path.endswith("/v3/auth/user/list"):
            users = [{"username": "nacos"}]
            if self.account_exists:
                users.append({"username": "bkagent"})
            return FakeResponse({"code": 0, "data": {"pageItems": users, "pagesAvailable": 1}})
        if url.path.endswith("/v3/auth/role/list"):
            roles = ([{"username": "bkagent", "role": init_auth.ROLE}]
                     if self.account_exists else [])
            return FakeResponse({"code": 0, "data": {"pageItems": roles, "pagesAvailable": 1}})
        if url.path.endswith("/v3/auth/permission/list"):
            permissions = ([{"role": init_auth.ROLE, "resource": init_auth.RESOURCE, "action": action}
                            for action in ("r", "w")]
                           if self.account_exists else [])
            return FakeResponse({"code": 0, "data": {"pageItems": permissions, "pagesAvailable": 1}})
        return FakeResponse({"code": 0, "data": "ok"})


class NacosA2aAuthTest(unittest.TestCase):
    def run_setup(self, opener):
        output = io.StringIO()
        environment = {
            "NACOS_NAMESPACE": "BK-AGENT",
            "NACOS_ADMIN_USERNAME": "nacos",
            "NACOS_ADMIN_PASSWORD": "admin-secret",
            "NACOS_USERNAME": "bkagent",
            "NACOS_PASSWORD": "service-secret",
        }
        with patch.dict(os.environ, environment, clear=True), \
                patch.object(init_auth.urllib.request, "build_opener", return_value=opener), \
                contextlib.redirect_stdout(output):
            init_auth.main()
        return output.getvalue()

    def test_creates_scoped_service_account_role_and_permission(self):
        opener = FakeOpener()

        output = self.run_setup(opener)

        calls = [(method, path) for method, path, _ in opener.calls]
        self.assertIn(("POST", "/nacos/v3/auth/user"), calls)
        self.assertIn(("POST", "/nacos/v3/auth/role"), calls)
        self.assertIn(("POST", "/nacos/v3/auth/permission"), calls)
        permissions = [values for method, path, values in opener.calls
                       if method == "POST" and path.endswith("/v3/auth/permission")]
        self.assertEqual(2, len(permissions))
        self.assertTrue(all(values["resource"] == ["BK-AGENT:DEFAULT_GROUP:ai/*"] for values in permissions))
        self.assertEqual({"r", "w"}, {values["action"][0] for values in permissions})
        self.assertNotIn("admin-secret", output)
        self.assertNotIn("service-secret", output)

    def test_existing_account_is_verified_without_recreating_roles(self):
        opener = FakeOpener(account_exists=True)

        output = self.run_setup(opener)

        self.assertFalse(any(path.endswith("/v3/auth/user") and method in ("POST", "PUT")
                             for method, path, _ in opener.calls))
        self.assertFalse(any(path.endswith("/v3/auth/role") and method == "POST"
                             for method, path, _ in opener.calls))
        self.assertFalse(any(path.endswith("/v3/auth/permission") and method == "POST"
                             for method, path, _ in opener.calls))
        self.assertNotIn("service-secret", output)


if __name__ == "__main__":
    unittest.main()
