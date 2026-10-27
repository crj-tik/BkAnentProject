#!/usr/bin/env python3
"""Publish allowlisted development templates with explicit auth mode and read-back verification."""
import hashlib
import json
import os
import time
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request

ALLOWED = frozenset({"auth-service.yaml", "rag-service.yaml", "listing-master-service.yaml", "memory-service.yaml", "agent-service.yaml", "customer-service.yaml", "marketing-content-service.yaml", "interview-service.yaml", "gateway.yaml", "business-review-service.yaml"})

def main():
    namespace = os.environ.get("NACOS_NAMESPACE", "")
    group = os.environ.get("NACOS_CONFIG_GROUP", "DEFAULT_GROUP")
    selected = os.environ.get("NACOS_CONFIG_DATA_IDS", "").split()
    base = os.environ.get("NACOS_PUBLISH_BASE", "http://nacos:8848")
    if base not in ("http://nacos:8848", "http://127.0.0.1:8848"):
        raise ValueError("Unapproved Nacos publication target")
    if namespace != "BK-AGENT" or group != "DEFAULT_GROUP" or not selected or not set(selected).issubset(ALLOWED) or len(set(selected)) != len(selected):
        raise ValueError("Explicit BK-AGENT/DEFAULT_GROUP authorized data IDs required")
    auth_setting = os.environ.get("NACOS_AUTH_ENABLE", "")
    if auth_setting not in ("true", "false"):
        raise ValueError("Explicit Nacos authentication mode required")
    config = Path(os.environ.get("NACOS_TEMPLATE_DIR", "/config"))
    contents = {name: (config / name).read_text(encoding="utf-8") for name in selected}
    class RejectRedirects(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            raise ValueError("Nacos redirects are not authorized")

    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), RejectRedirects())
    def request(path, values, method="POST"):
        encoded = urllib.parse.urlencode(values).encode()
        url = base + path
        if method == "GET":
            url += "?" + encoded.decode()
            encoded = None
        with opener.open(urllib.request.Request(url, data=encoded, method=method), timeout=15) as response:
            return response.read()
    token = None
    if auth_setting == "true":
        username = os.environ.get("NACOS_USERNAME", "")
        password = os.environ.get("NACOS_PASSWORD", "")
        if not username or not password:
            raise ValueError("Explicit Nacos login credentials required")
        login = json.loads(request("/nacos/v3/auth/user/login", {"username": username, "password": password}))
        payload = login.get("data", login) if isinstance(login, dict) else None
        token = payload.get("accessToken") if isinstance(payload, dict) else None
        if not isinstance(token, str) or not token:
            raise ValueError("Nacos login did not return a token")
    for name, content in contents.items():
        values = {"tenant": namespace, "group": group, "dataId": name}
        if token:
            values["accessToken"] = token
        result = request("/nacos/v1/cs/configs", {**values, "type": "yaml", "content": content})
        if result.strip() != b"true":
            raise ValueError("Nacos rejected publication")
        # Nacos persists then asynchronously refreshes its read cache. Retry reads only.
        deadline = time.monotonic() + 30
        while True:
            try:
                actual = request("/nacos/v1/cs/configs", values, "GET").decode()
                if actual.replace("\r\n", "\n") == content.replace("\r\n", "\n"):
                    break
            except urllib.error.HTTPError as exc:
                if exc.code != 404:
                    raise
            if time.monotonic() >= deadline:
                raise ValueError("Nacos read-back differs from selected template")
            time.sleep(1)
        print("Verified publication", namespace, group, name, "sha256=" + hashlib.sha256(actual.encode()).hexdigest())
    # No credentials, login response, or configuration content is printed or written.

if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, urllib.error.HTTPError, json.JSONDecodeError) as exc:
        print("Nacos publication failed:", type(exc).__name__, "(response/credentials withheld)")
        raise SystemExit(1)
