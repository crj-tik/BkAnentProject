#!/usr/bin/env python3
"""Initialize the local Nacos A2A service account and namespace-scoped write permission."""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.parse
import urllib.request


BASE = "http://nacos:8848/nacos"
NAMESPACE = "BK-AGENT"
GROUP = "DEFAULT_GROUP"
ROLE = "bkagent-a2a-card-writer"
RESOURCE = f"{NAMESPACE}:{GROUP}:ai/*"


class ApiFailure(RuntimeError):
    pass


class NoRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        raise ApiFailure("Nacos authorization endpoint redirected")


def request_json(opener, method: str, path: str, values: dict[str, object] | None = None,
                 token: str | None = None) -> tuple[int, dict[str, object]]:
    values = values or {}
    headers = {"Content-Type": "application/x-www-form-urlencoded"}
    if token:
        headers["accessToken"] = token
    if method == "GET":
        query = urllib.parse.urlencode(values)
        url = f"{BASE}{path}?{query}" if query else f"{BASE}{path}"
        body = None
    else:
        url = f"{BASE}{path}"
        body = urllib.parse.urlencode(values).encode("utf-8")
    request = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with opener.open(request, timeout=10) as response:
            payload = json.loads(response.read().decode("utf-8"))
            return response.status, payload if isinstance(payload, dict) else {}
    except urllib.error.HTTPError as error:
        try:
            payload = json.loads(error.read().decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            payload = {}
        return error.code, payload if isinstance(payload, dict) else {}


def require_success(result: tuple[int, dict[str, object]], action: str) -> dict[str, object]:
    status, payload = result
    if status < 200 or status >= 300 or payload.get("code") not in (0, "0"):
        raise ApiFailure(f"Nacos {action} failed (HTTP {status}, code {payload.get('code', 'n/a')})")
    return payload


def response_data(payload: dict[str, object]) -> dict[str, object]:
    data = payload.get("data", payload)
    return data if isinstance(data, dict) else {}


def login(opener, username: str, password: str) -> str | None:
    status, payload = request_json(opener, "POST", "/v3/auth/user/login",
                                   {"username": username, "password": password})
    if status < 200 or status >= 300 or payload.get("code") not in (None, 0, "0"):
        return None
    token = response_data(payload).get("accessToken")
    return token if isinstance(token, str) and token else None


def admin_token(opener, username: str, password: str) -> str:
    token = login(opener, username, password)
    if token:
        return token
    if username != "nacos":
        raise ApiFailure("Nacos admin login failed; only the built-in nacos account can be bootstrapped")
    status, payload = request_json(opener, "POST", "/v3/auth/user/admin", {"password": password})
    if status < 200 or status >= 300 or payload.get("code") not in (0, "0"):
        raise ApiFailure("Nacos admin login and one-time password initialization both failed")
    token = login(opener, username, password)
    if not token:
        raise ApiFailure("Nacos admin was initialized but could not log in")
    print("Nacos built-in administrator initialized")
    return token


def list_items(opener, path: str, token: str, **filters: str) -> list[dict[str, object]]:
    items: list[dict[str, object]] = []
    page = 1
    while True:
        payload = require_success(request_json(
            opener, "GET", path,
            {"pageNo": page, "pageSize": 200, **filters}, token), path)
        data = response_data(payload)
        page_items = data.get("pageItems", [])
        if isinstance(page_items, list):
            items.extend(item for item in page_items if isinstance(item, dict))
        pages = data.get("pagesAvailable", 1)
        if not isinstance(pages, int) or page >= pages:
            return items
        page += 1


def ensure_account(opener, token: str, username: str, password: str) -> None:
    users = list_items(opener, "/v3/auth/user/list", token)
    if any(user.get("username") == username for user in users):
        if not login(opener, username, password):
            raise ApiFailure("Existing Nacos A2A account password differs from the configured secret")
    else:
        require_success(request_json(opener, "POST", "/v3/auth/user",
                                     {"username": username, "password": password}, token),
                        "service-account creation")

    roles = list_items(opener, "/v3/auth/role/list", token, username=username)
    if not any(role.get("role") == ROLE and role.get("username") == username for role in roles):
        require_success(request_json(opener, "POST", "/v3/auth/role",
                                     {"role": ROLE, "username": username}, token), "role assignment")

    permissions = list_items(opener, "/v3/auth/permission/list", token, role=ROLE)
    for action in ("r", "w"):
        if not any(permission.get("resource") == RESOURCE and permission.get("action") == action
                   for permission in permissions):
            require_success(request_json(opener, "POST", "/v3/auth/permission",
                                         {"role": ROLE, "resource": RESOURCE, "action": action}, token),
                            f"A2A namespace {action} permission")

    if not login(opener, username, password):
        raise ApiFailure("Nacos A2A service account could not log in after synchronization")


def main() -> None:
    namespace = os.environ.get("NACOS_NAMESPACE", "")
    admin_user = os.environ.get("NACOS_ADMIN_USERNAME", "nacos")
    admin_password = os.environ.get("NACOS_ADMIN_PASSWORD", "")
    service_user = os.environ.get("NACOS_USERNAME", "")
    service_password = os.environ.get("NACOS_PASSWORD", "")
    if namespace != NAMESPACE:
        raise ApiFailure("Nacos A2A setup is restricted to the BK-AGENT namespace")
    if not admin_password or not service_user or not service_password:
        raise ApiFailure("Explicit Nacos admin and A2A service-account credentials are required")
    if service_user == admin_user:
        raise ApiFailure("Nacos A2A service account must be separate from the global administrator")

    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirects())
    token = admin_token(opener, admin_user, admin_password)
    ensure_account(opener, token, service_user, service_password)
    print(f"Nacos A2A authorization ready: namespace={NAMESPACE} user={service_user} resource={RESOURCE} actions=r,w")


if __name__ == "__main__":
    try:
        main()
    except (ApiFailure, OSError, urllib.error.URLError, json.JSONDecodeError) as error:
        print("Nacos A2A authorization setup failed:", type(error).__name__, str(error))
        raise SystemExit(1)
