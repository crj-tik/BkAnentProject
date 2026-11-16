#!/usr/bin/env python3
"""真实 API 验证 UCID 及已有业务资源隔离；凭据仅读取被忽略的 .env。"""
import argparse
import importlib.util
import sys
from pathlib import Path

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location(
    "ucid_api_helpers", ROOT / "scripts/acceptance/user-organization-role-api-smoke.py")
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--customer-id", type=int, required=True, help="测试账号已确认负责的客户 ID")
    parser.add_argument("--listing-id", type=int, required=True, help="测试账号已确认负责的房源 ID")
    parser.add_argument("--owner-env-prefix", choices=("AUTH_BROKER", "API_ACCEPTANCE"), default="AUTH_BROKER",
                        help=".env 中保存资源负责人账号的变量前缀")
    args = parser.parse_args()
    env = api.read_env()
    host = env.get("HOST_BIND_ADDRESS", "127.0.0.1") or "127.0.0.1"
    if host in ("0.0.0.0", "::"):
        host = "127.0.0.1"
    if host not in ("127.0.0.1", "localhost", "::1"):
        raise RuntimeError("验收仅允许本机开发 Gateway")
    if host == "::1":
        host = "[::1]"
    base = f"http://{host}:{env.get('GATEWAY_EXPOSED_PORT', '5010') or '5010'}"
    tokens = []
    try:
        owner = api.login(base, env.get(args.owner_env_prefix + "_USERNAME"),
                          env.get(args.owner_env_prefix + "_PASSWORD"))
        tokens.append(owner["accessToken"])
        other = api.login(base, env.get("ORG_ACCEPTANCE_OPERATIONS_USERNAME"),
                          env.get("ORG_ACCEPTANCE_OPERATIONS_PASSWORD"))
        tokens.append(other["accessToken"])
        owner_me = api.profile(base, tokens[0], "业务用户 /me")
        other_me = api.profile(base, tokens[1], "另一用户 /me")
        api.require(owner_me["userId"] != other_me["userId"], "两个独立 UCID")
        for path in (f"/customers/{args.customer_id}", f"/listings/{args.listing_id}"):
            status, body = api.call(base, "GET", path, token=tokens[0])
            api.success_data(status, body, "本人业务详情")
            status, body = api.call(base, "GET", path, token=tokens[1])
            api.require(status == 403, "其他用户私有业务详情拒绝", status, body)
        status, body = api.call(base, "GET", "/notifications", token=tokens[0])
        api.success_data(status, body, "本人通知")
        status, body = api.call(base, "GET", f"/notifications?userId={other_me['userId']}", token=tokens[0])
        api.require(status == 403, "通知收件 UCID 替换拒绝", status, body)
        status, body = api.call(base, "GET", "/marketing/contents?listingId=" + str(args.listing_id), token=tokens[0])
        api.success_data(status, body, "已授权营销查询")
        status, body = api.call(base, "POST", "/agent/supervisor/tasks", token=tokens[0],
                                payload={"userId": str(other_me["userId"]), "userMessage": "身份边界探测"})
        api.require(status == 403, "Agent UCID 替换拒绝", status, body)
        print("UCID API 验收通过：本人客户/房源读取、其他用户拒绝、本人通知、收件人替换拒绝、营销查询、Agent actor 替换拒绝")
    finally:
        for token in tokens:
            api.call(base, "POST", "/auth/logout", token=token)


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, KeyError) as error:
        print(f"UCID API 验收失败：{error}", file=sys.stderr)
        sys.exit(1)
