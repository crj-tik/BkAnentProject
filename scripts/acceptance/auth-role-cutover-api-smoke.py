#!/usr/bin/env python3
"""本机 API 验收：管理员角色切换、权限撤销刷新和组织成员迁移。"""
import importlib.util
import json
import sys
sys.dont_write_bytecode = True
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location(
    "auth_org_bootstrap", ROOT / "scripts/acceptance/bootstrap-auth-organizations.py")
bootstrap = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bootstrap)


def call(base, path, token=None, payload=None, method="GET"):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = None
    if payload is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    try:
        with urlopen(Request(base + path, data=data, headers=headers, method=method), timeout=30) as response:
            return response.status, json.load(response)
    except HTTPError as error:
        try:
            body = json.load(error)
        except (ValueError, OSError):
            body = {}
        return error.code, body
    except (URLError, TimeoutError, ValueError) as error:
        raise RuntimeError(f"{method} {path.split('?')[0]} failed: {type(error).__name__}") from None


def data(status, body, label):
    if not 200 <= status < 300 or isinstance(body, dict) and body.get("success") is False:
        raise RuntimeError(f"{label} rejected (HTTP {status})")
    return body.get("data", body) if isinstance(body, dict) else body


def all_users(base, token):
    rows = []
    page = 1
    while True:
        status, body = call(base, "/auth/users?" + urlencode({"page": page, "pageSize": 100}), token)
        result = data(status, body, "user inventory")
        rows.extend(result["records"])
        if page * 100 >= result["total"]:
            return rows
        page += 1


def profile(base, token):
    status, body = call(base, "/auth/me", token)
    return data(status, body, "/auth/me")


def mutate_role(base, token, role, permissions):
    payload = {"code": role["code"], "name": role["name"], "description": role.get("description"),
               "active": role["active"], "assignable": role["assignable"],
               "permissionCodes": permissions, "roleType": role.get("roleType")}
    status, body = call(base, f"/auth/authorization/roles/{role['id']}", token, payload, "PUT")
    data(status, body, "temporary acceptance role update")


def move_member(base, token, org_id, user_id, target_id):
    status, body = call(base, f"/auth/authorization/organizations/{org_id}/members/{user_id}/organization",
                        token, {"targetOrgId": target_id}, "PUT")
    result = data(status, body, "organization membership move")
    if not result.get("eventId"):
        raise RuntimeError("organization move did not produce an authorization event")
    return result


def verify_admin_cutover(base, admin_token, actor_id, users, roles, catalog):
    baseline_source = (ROOT / "common/src/main/java/com/bkanent/common/authz/AuthzOperationPolicy.java").read_text(
        encoding="utf-8")
    import re
    match = re.search(r"BASELINE_OPERATIONS\s*=\s*Set\.of\((.*?)\);", baseline_source, re.S)
    if not match:
        raise RuntimeError("cannot read the explicit baseline operation set")
    baseline = set(re.findall(r'"([a-z0-9._-]+)"', match.group(1)))
    restricted = {item["code"] for item in catalog
                  if item.get("active") and item["code"] not in baseline}
    role_map = {role["code"]: set(role.get("permissionCodes", [])) for role in roles}
    if role_map.get("PLATFORM_ADMIN") != restricted:
        raise RuntimeError("prepared administrator role differs from the active restricted catalog")

    active_admins = [user for user in users if user.get("businessAdmin")
                     and user.get("accountStatus") == "ACTIVE"]
    for user in active_admins:
        if "PLATFORM_ADMIN" in (user.get("roles") or []):
            continue
        status, body = call(base, f"/auth/authorization/users/{user['userId']}/platform-administrator",
                            admin_token, {"enabled": True}, "PUT")
        data(status, body, "administrator role migration")

    # The API itself checks that no legacy administrator remains and that at least one
    # role-based administrator has the exact active catalog before switching policy.
    status, body = call(base, "/auth/authorization/policy/role-only", admin_token, method="PUT")
    data(status, body, "role-only authorization cutover")

    current = all_users(base, admin_token)
    admins = [user for user in current if user.get("businessAdmin")
              and user.get("accountStatus") == "ACTIVE"]
    if not admins or any("PLATFORM_ADMIN" not in (user.get("roles") or []) for user in admins):
        raise RuntimeError("role-only cutover did not preserve all active administrators")
    if not any(str(user["userId"]) == str(actor_id) for user in admins):
        raise RuntimeError("current operator lost administrator access during cutover")

    # Prove the last-admin guard without leaving the development database changed.
    other = next((user for user in admins if str(user["userId"]) != str(actor_id)), None)
    if other is None:
        raise RuntimeError("at least two active administrators are required for the last-admin probe")
    other_id = other["userId"]
    status, body = call(base, f"/auth/authorization/users/{other_id}/platform-administrator",
                        admin_token, {"enabled": False}, "PUT")
    data(status, body, "temporary administrator demotion")
    try:
        status, _ = call(base, f"/auth/authorization/users/{actor_id}/platform-administrator",
                         admin_token, {"enabled": False}, "PUT")
        if status != 409:
            raise RuntimeError(f"last active administrator removal was not rejected (HTTP {status})")
    finally:
        status, body = call(base, f"/auth/authorization/users/{other_id}/platform-administrator",
                            admin_token, {"enabled": True}, "PUT")
        data(status, body, "restore administrator after last-admin probe")

    if not profile(base, admin_token).get("businessAdmin"):
        raise RuntimeError("administrator access was not retained after last-admin probe")
    return len(admins)


def verify_revocation_freshness(base, admin_token, config):
    username = config.get("ORG_ACCEPTANCE_OPERATIONS_USERNAME")
    password = config.get("ORG_ACCEPTANCE_OPERATIONS_PASSWORD")
    if not username or not password:
        raise RuntimeError("local operations acceptance credentials are missing")
    status, body = call(base, "/auth/authorization/roles", admin_token)
    roles = data(status, body, "role list")
    role = next((item for item in roles if item.get("code") == "ACCEPTANCE_OPERATIONS"), None)
    if role is None or role.get("permissionCodes"):
        raise RuntimeError("operations acceptance role must start with no restricted permissions")
    status, body = call(base, "/auth/authorization/catalog", admin_token)
    catalog = data(status, body, "permission catalog")
    permission = "business.kpi.read.all"
    if not any(item.get("code") == permission and item.get("active") for item in catalog):
        raise RuntimeError("freshness probe permission is not active in the catalog")

    probe_path = "/platform-operations/analytics/kpis/monthly?month=2026-10"
    started = time.monotonic()
    try:
        mutate_role(base, admin_token, role, [permission])
        probe_token = bootstrap.login(base, username, password)
        status, body = call(base, probe_path, probe_token)
        data(status, body, "freshness probe with granted permission")

        mutate_role(base, admin_token, role, [])
        deadline = time.monotonic() + 30
        while True:
            status, _ = call(base, probe_path, probe_token)
            if status == 403:
                break
            if status not in (200, 202):
                raise RuntimeError(f"freshness probe returned unexpected HTTP {status}")
            if time.monotonic() >= deadline:
                raise RuntimeError("permission revocation did not take effect within 30 seconds")
            time.sleep(1)
        elapsed = time.monotonic() - started
        if elapsed > 30:
            raise RuntimeError("permission revocation exceeded the 30-second freshness bound")
        return elapsed
    finally:
        # The fixture role and its single test user must return to the documented least-privilege state.
        status, body = call(base, "/auth/authorization/roles", admin_token)
        if 200 <= status < 300:
            current = data(status, body, "role list during cleanup")
            role = next((item for item in current if item.get("code") == "ACCEPTANCE_OPERATIONS"), None)
            if role is not None and role.get("permissionCodes"):
                mutate_role(base, admin_token, role, [])


def verify_membership_versions(base, admin_token, config):
    username = config.get("ORG_ACCEPTANCE_BROKER_USERNAME")
    password = config.get("ORG_ACCEPTANCE_BROKER_PASSWORD")
    if not username or not password:
        raise RuntimeError("local broker acceptance credentials are missing")
    token = bootstrap.login(base, username, password)
    actor = profile(base, token)
    actor_id = actor["userId"]
    status, body = call(base, "/auth/authorization/organizations", admin_token)
    orgs = data(status, body, "organization list")
    store = next((item for item in orgs if item.get("code") == "ACCEPTANCE_STORE"), None)
    department = next((item for item in orgs if item.get("code") == "ACCEPTANCE_OPERATIONS"), None)
    if store is None or department is None:
        raise RuntimeError("acceptance store or operations department is missing")
    store_id, department_id = store["id"], department["id"]
    current = next((item for item in actor.get("organizations", []) if item.get("primary")), None)
    if current is None or str(current.get("id")) != str(store_id):
        raise RuntimeError("broker does not have the expected primary store membership")

    current_org_id = store_id
    initial_revision = int(actor.get("permissionRevision") or 0)
    try:
        move = move_member(base, admin_token, store_id, actor_id, department_id)
        current_org_id = department_id
        moved = profile(base, token)
        if int(moved.get("permissionRevision") or 0) <= initial_revision:
            raise RuntimeError("membership move did not advance the authorization revision")
        if not any(str(item.get("id")) == str(department_id) and item.get("primary")
                   for item in moved.get("organizations", [])):
            raise RuntimeError("moved broker profile does not show the department as primary")

        restored = move_member(base, admin_token, department_id, actor_id, store_id)
        current_org_id = store_id
        final = profile(base, token)
        if int(final.get("permissionRevision") or 0) <= int(moved.get("permissionRevision") or 0):
            raise RuntimeError("membership restore did not advance the authorization revision")
        if not any(str(item.get("id")) == str(store_id) and item.get("primary")
                   for item in final.get("organizations", [])):
            raise RuntimeError("broker primary store membership was not restored")
        return move.get("eventId"), restored.get("eventId")
    finally:
        if str(current_org_id) != str(store_id):
            move_member(base, admin_token, current_org_id, actor_id, store_id)


def main():
    _, config = bootstrap.read_env()
    host = config.get("HOST_BIND_ADDRESS", "127.0.0.1") or "127.0.0.1"
    if host in ("0.0.0.0", "::", ""):
        host = "127.0.0.1"
    base = f"http://{host}:{config.get('GATEWAY_EXPOSED_PORT', '5010') or '5010'}"
    if urlsplit(base).hostname not in ("127.0.0.1", "localhost", "::1"):
        raise RuntimeError("acceptance is restricted to the local development Gateway")
    username = config.get("AUTH_ADMIN_USERNAME")
    password = config.get("AUTH_ADMIN_PASSWORD")
    if not username or not password:
        raise RuntimeError("local administrator acceptance credentials are missing")
    admin_token = bootstrap.login(base, username, password)
    actor_id = profile(base, admin_token)["userId"]

    status, body = call(base, "/auth/authorization/roles", admin_token)
    roles = data(status, body, "role list")
    status, body = call(base, "/auth/authorization/catalog", admin_token)
    catalog = data(status, body, "permission catalog")
    admins = verify_admin_cutover(base, admin_token, actor_id, all_users(base, admin_token), roles, catalog)
    elapsed = verify_revocation_freshness(base, admin_token, config)
    moved, restored = verify_membership_versions(base, admin_token, config)
    print(f"Auth API 验收通过：{admins} 名管理员切换到显式角色并启用 role-only；最后管理员保护拒绝生效；"
          f"权限撤销在 {elapsed:.2f}s 内生效；成员移动与恢复均递增授权版本并写入 outbox。")


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, KeyError, TypeError) as error:
        print(f"Auth API 验收失败：{error}", file=sys.stderr)
        sys.exit(1)
