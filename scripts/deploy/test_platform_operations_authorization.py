#!/usr/bin/env python3
"""Guard the unified platform Agent authorization wiring and retired entry points."""

from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def require(value: bool, message: str) -> None:
    if not value:
        print(message, file=sys.stderr)
        raise SystemExit(1)


def main() -> None:
    auth = read("nacos/auth-service.yaml")
    agent = read("nacos/agent-service.yaml")
    app = read("agent-service/src/main/resources/application.yml")
    platform_nacos = read("nacos/platform-operations-service.yaml")
    migration = read("sql/migrations/20261030_retire_platform_legacy_authorization.sql")
    check = read("docker/mysql/checks/retired-platform-legacy-authorization.sql")
    platform_app = read("platform-operations-service/src/main/resources/application.yml")

    caller = re.search(r"(?ms)^      agent-service-current:\n(.*?)(?=^      [a-zA-Z0-9_-]+:\n|^    redis:)", auth)
    require(caller is not None, "Auth Nacos template must trust the Agent service identity")
    caller_text = caller.group(1)
    for value in ("service: agent-service", "${AGENT_SERVICE_IDENTITY_PUBLIC_KEY:}",
                  "operations: [BIND_EXECUTION]", "audiences: [agent-service]", "permission-codes:"):
        require(value in caller_text, f"Auth Agent trusted caller is missing {value}")
    codes = re.findall(r"(?m)^          - ([a-z][a-z0-9_-]*(?:\.[a-z][a-z0-9_-]*)+)$", caller_text)
    require(len(codes) >= 100 and len(codes) == len(set(codes)),
            "Auth Agent trusted caller needs a unique, explicit active permission catalog")
    retired = ("agent.workflow.invoke.knowledge", "agent.workflow.invoke.business-review",
               "agent.workflow.invoke.platform-operations")
    require(not any(code.startswith("contract.") or code.startswith("settlement.")
                    or code in retired for code in codes), "Retired permission leaked into Agent trusted caller ACL")
    require({"agent.workflow.invoke.platform-operations.analytics",
             "agent.workflow.invoke.platform-operations.review",
             "agent.workflow.invoke.platform-operations.knowledge"}.issubset(codes),
            "Agent trusted caller omits a platform capability operation")

    for config in (agent, app):
        require('"[a2a:platform-operations-agent]": agent.chat.use' in config,
                "Unified platform Agent transport must use the baseline invocation operation")
        require('"[a2a:knowledge-agent]"' not in config and '"[a2a:business-review-agent]"' not in config,
                "Removed Knowledge and Business Review Agent mappings remain active")
    require("max-http-request-header-size: 64KB" in platform_app
            and "max-http-request-header-size: 64KB" in platform_nacos
            and "max-http-request-header-size: 64KB" in agent,
            "Platform service and Compose must allow bounded signed authorization context headers")
    require("SERVER_MAX_HTTP_REQUEST_HEADER_SIZE: ${SERVER_MAX_HTTP_REQUEST_HEADER_SIZE:-64KB}" in read("docker-compose.yml"),
            "Compose must expose the bounded authorization header setting")

    for value in ("20261030_retire_platform_legacy_authorization", "auth_role_permission",
                  "auth_user_authorization_state", "auth_authorization_outbox"):
        require(value in migration, f"Permission retirement migration is missing {value}")
    require("contract.%" in check and "settlement.%" in check
            and "20261030_retire_platform_legacy_authorization" in check,
            "Permission retirement check does not cover the retired catalog")

    print(f"OK: unified Agent caller, {len(codes)} exact permissions, legacy permission retirement")


if __name__ == "__main__":
    main()
