#!/usr/bin/env python3
"""Retire explicitly allowlisted legacy service data IDs from local development Nacos."""

import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request


RETIRED = frozenset({
    "business-service.yaml",
    "business-review-service.yaml",
    "knowledge-service.yaml",
    "compare-engine-service.yaml",
    "contract-service.yaml",
    "settlement-service.yaml",
    "promotion-service.yaml",
})


def main() -> None:
    namespace = os.environ.get("NACOS_NAMESPACE", "")
    group = os.environ.get("NACOS_CONFIG_GROUP", "DEFAULT_GROUP")
    base = os.environ.get("NACOS_PUBLISH_BASE", "http://nacos:8848")
    dry_run = os.environ.get("NACOS_RETIRE_DRY_RUN", "true")
    if base not in ("http://nacos:8848", "http://127.0.0.1:8848"):
        raise ValueError("Unapproved local Nacos target")
    if namespace != "BK-AGENT" or group != "DEFAULT_GROUP":
        raise ValueError("Explicit BK-AGENT/DEFAULT_GROUP target required")
    if dry_run not in ("true", "false"):
        raise ValueError("NACOS_RETIRE_DRY_RUN must be true or false")

    class RejectRedirects(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            raise ValueError("Nacos redirects are not authorized")

    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), RejectRedirects())

    def request(path: str, values: dict[str, str], method: str = "GET") -> bytes:
        url = base + path + "?" + urllib.parse.urlencode(values)
        with opener.open(urllib.request.Request(url, method=method), timeout=15) as response:
            return response.read()

    def status(name: str, token: str | None) -> int:
        values = {"tenant": namespace, "group": group, "dataId": name}
        if token:
            values["accessToken"] = token
        try:
            request("/nacos/v1/cs/configs", values, "GET")
            return 200
        except urllib.error.HTTPError as exc:
            if exc.code == 404:
                return 404
            raise

    token = None
    auth_setting = os.environ.get("NACOS_AUTH_ENABLE", "")
    if auth_setting not in ("true", "false"):
        raise ValueError("Explicit Nacos authentication mode required")
    if auth_setting == "true":
        username = os.environ.get("NACOS_ADMIN_USERNAME", "")
        password = os.environ.get("NACOS_ADMIN_PASSWORD", "")
        if not username or not password:
            raise ValueError("Explicit Nacos Admin retirement credentials required")
        login = json.loads(request("/nacos/v3/auth/user/login", {
            "username": username,
            "password": password,
        }))
        payload = login.get("data", login) if isinstance(login, dict) else None
        token = payload.get("accessToken") if isinstance(payload, dict) else None
        if not isinstance(token, str) or not token:
            raise ValueError("Nacos login did not return a token")

    present = []
    for name in sorted(RETIRED):
        if status(name, token) == 200:
            present.append(name)
    if not present:
        print("No retired data IDs are present in", namespace, group)
        return

    action = "Would retire" if dry_run == "true" else "Retiring"
    for name in present:
        print(action, namespace, group, name)
    if dry_run == "true":
        print("Dry run only; set NACOS_RETIRE_DRY_RUN=false to remove this exact allowlist.")
        return

    for name in present:
        values = {"tenant": namespace, "group": group, "dataId": name}
        if token:
            values["accessToken"] = token
        try:
            result = request("/nacos/v1/cs/configs", values, "DELETE")
        except urllib.error.HTTPError as exc:
            if exc.code == 404:
                continue
            raise
        if result.strip().lower() not in (b"true", b"ok"):
            raise ValueError("Nacos rejected retirement")

    deadline = time.monotonic() + 30
    remaining = set(present)
    while remaining and time.monotonic() < deadline:
        for name in tuple(remaining):
            if status(name, token) == 404:
                remaining.remove(name)
        if remaining:
            time.sleep(1)
    if remaining:
        raise ValueError("Nacos read-back still finds retired data IDs")
    for name in present:
        print("Verified retired", namespace, group, name)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, urllib.error.HTTPError, json.JSONDecodeError) as exc:
        print("Nacos retirement failed:", type(exc).__name__, "(response/credentials withheld)")
        raise SystemExit(1)
