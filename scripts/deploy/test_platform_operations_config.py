#!/usr/bin/env python3
"""Static cross-file checks for the consolidated Platform Operations domain."""

from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def require(text: str, expected: str, source: str) -> None:
    if expected not in text:
        print(f"{source} is missing {expected}", file=sys.stderr)
        raise SystemExit(1)


def main() -> None:
    env = read(".env.example")
    compose = read("docker-compose.yml")
    app = read("platform-operations-service/src/main/resources/application.yml")
    nacos = read("nacos/platform-operations-service.yaml")
    memory = read("nacos/memory-service.yaml")
    agent = read("nacos/agent-service.yaml")
    gateway = read("nacos/gateway.yaml")
    rag = read("nacos/rag-service.yaml")
    publish = read("scripts/deploy/publish-nacos.py")
    nacos_auth = read("scripts/deploy/init-nacos-a2a-auth.py")
    retire = read("scripts/deploy/retire-nacos-configs.py")
    init = read("scripts/deploy/init-environment.sh")
    start = read("scripts/deploy/start.sh")
    stop = read("scripts/deploy/stop.sh")
    keygen = read("scripts/security/ServiceIdentityKeygen.java")

    for key in (
        "MYSQL_DB_PLATFORM_OPERATIONS", "PLATFORM_OPERATIONS_DB_USER", "PLATFORM_OPERATIONS_DB_PASSWORD",
        "PLATFORM_OPERATIONS_SOURCE_DB_USER", "PLATFORM_OPERATIONS_SOURCE_DB_PASSWORD",
        "PLATFORM_OPERATIONS_SOURCE_MODE", "PLATFORM_OPERATIONS_LISTING_BASE_URL",
        "PLATFORM_OPERATIONS_MEMORY_V2_COMPRESSION_ENABLED",
        "NACOS_ADMIN_USERNAME", "NACOS_ADMIN_PASSWORD", "NACOS_USERNAME", "NACOS_PASSWORD",
        "NACOS_AUTH_ADMIN_ENABLE", "NACOS_AUTH_CONSOLE_ENABLE",
    ):
        require(env, f"{key}=", ".env.example")
    require(compose, "x-a2a-agent-environment: &a2a-agent-environment", "Docker Compose A2A credential scope")
    require(compose, "NACOS_USERNAME: ${NACOS_USERNAME:-bkagent}", "Docker Compose A2A credential scope")
    require(compose, "NACOS_PASSWORD: ${NACOS_PASSWORD:-}", "Docker Compose A2A credential scope")
    require(compose, "NACOS_AUTH_ADMIN_ENABLE: ${NACOS_AUTH_ADMIN_ENABLE:-true}", "Nacos Admin API auth")
    require(compose, "NACOS_AUTH_CONSOLE_ENABLE: ${NACOS_AUTH_CONSOLE_ENABLE:-true}", "Nacos console auth")
    for value in (
        "NACOS_ADMIN_USERNAME: ${NACOS_ADMIN_USERNAME:-nacos}",
        "NACOS_ADMIN_PASSWORD: ${NACOS_ADMIN_PASSWORD:-}",
        "init-nacos-a2a-auth.py",
        "python /scripts/init-nacos-a2a-auth.py && python /scripts/publish-nacos.py",
    ):
        require(compose, value, "Docker Compose Nacos initialization")
    require(nacos_auth, 'RESOURCE = f"{NAMESPACE}:{GROUP}:ai/*"', "Nacos A2A permission scope")
    require(nacos_auth, '"/v3/auth/user/admin"', "Nacos Admin bootstrap endpoint")
    for template_path in (ROOT / "nacos").glob("*.yaml"):
        template = template_path.read_text(encoding="utf-8")
        if "${NACOS_USERNAME:}" in template or "${NACOS_PASSWORD:}" in template:
            service = template_path.stem
            block_match = re.search(rf"(?ms)^  {re.escape(service)}:\n(.*?)(?=^  [a-zA-Z0-9_-]+:\n|^volumes:)", compose)
            if not block_match:
                raise SystemExit(f"docker-compose.yml has no service block for A2A template {template_path.name}")
            require(block_match.group(1), "<<: [*app-environment, *a2a-agent-environment]",
                    f"Scoped A2A Compose credentials for {template_path.name}")
    for service in ("auth-service", "gateway", "customer-service", "memory-service"):
        block_match = re.search(rf"(?ms)^  {service}:\n(.*?)(?=^  [a-zA-Z0-9_-]+:\n|^volumes:)", compose)
        if block_match and "*a2a-agent-environment" in block_match.group(1):
            raise SystemExit(f"Unneeded A2A registry credentials are passed to {service}")
    require(compose, "SPRING_CLOUD_NACOS_USERNAME: ${NACOS_CLIENT_USERNAME:-}", "RAG Nacos client credentials")
    require(compose, "SPRING_CLOUD_NACOS_PASSWORD: ${NACOS_CLIENT_PASSWORD:-}", "RAG Nacos client credentials")
    shared_environment = re.search(r"(?ms)^x-app-environment: &app-environment\n(.*?)(?=^x-a2a-agent-environment:)", compose)
    if not shared_environment or re.search(r"(?m)^\s*NACOS_USERNAME:", shared_environment.group(1)):
        raise SystemExit("A2A AgentCard credentials must not be passed to every application container")
    rag_block = re.search(r"(?ms)^  rag-service:\n(.*?)(?=^  [a-zA-Z0-9_-]+:\n|^volumes:)", compose)
    if not rag_block or re.search(r"(?m)^\s*NACOS_(?:USERNAME|PASSWORD):", rag_block.group(1)):
        raise SystemExit("RAG must use the Nacos configuration-client identity, not the A2A identity")
    require(publish, 'os.environ.get("NACOS_ADMIN_USERNAME", "")', "Nacos config publication identity")
    require(publish, 'os.environ.get("NACOS_ADMIN_PASSWORD", "")', "Nacos config publication identity")
    require(retire, 'os.environ.get("NACOS_ADMIN_USERNAME", "")', "Nacos config retirement identity")
    require(retire, 'os.environ.get("NACOS_ADMIN_PASSWORD", "")', "Nacos config retirement identity")
    require(nacos_auth, 'for action in ("r", "w")', "Scoped A2A registry read/write permissions")
    for retired in ("MYSQL_DB_KNOWLEDGE", "KNOWLEDGE_", "BUSINESS_REVIEW_", "MYSQL_DB_BUSINESS_REVIEW"):
        if retired in env:
            raise SystemExit(f".env.example still contains retired service configuration: {retired}")

    block_match = re.search(r"(?ms)^  platform-operations-service:\n(.*?)(?=^  [a-zA-Z0-9_-]+:\n|^volumes:)", compose)
    if not block_match:
        raise SystemExit("docker-compose.yml has no platform-operations-service block")
    block = block_match.group(1)
    for value in (
        "profiles: [full, platform-preview]", "MAVEN_MODULES: platform-operations-service",
        "SERVICE_ARTIFACT: platform-operations-service-1.0.0-SNAPSHOT.jar",
        "source: ./.secrets/service-identities/platform-operations-service/private-key.pem",
        "PLATFORM_OPERATIONS_SOURCE_MODE:", "PLATFORM_OPERATIONS_LISTING_BASE_URL:",
        "SERVICE_READINESS_REQUIRED_DEPENDENCIES: nacos,database,redis,memory",
    ):
        require(block, value, "docker-compose.yml platform-operations-service")
    listing_block_match = re.search(r"(?ms)^  listing-master-service:\n(.*?)(?=^  [a-zA-Z0-9_-]+:\n|^volumes:)", compose)
    if not listing_block_match:
        raise SystemExit("docker-compose.yml has no listing-master-service block")
    require(listing_block_match.group(1), "profiles: [full, platform-preview]",
            "platform-preview listing dependency")
    if "MYSQL_ROOT_PASSWORD" in block:
        raise SystemExit("platform-operations-service must not receive the MySQL root password")
    if re.search(r"(?m)^  (?:business-service|business-review-service|knowledge-service):", compose):
        raise SystemExit("docker-compose.yml still declares a retired business service")

    for value in (
        "PLATFORM_OPERATIONS_DB_USER", "PLATFORM_OPERATIONS_DB_PASSWORD",
        "PLATFORM_OPERATIONS_SOURCE_DB_USER", "PLATFORM_OPERATIONS_SOURCE_DB_PASSWORD",
        "PLATFORM_OPERATIONS_SOURCE_MODE", "PLATFORM_OPERATIONS_LISTING_BASE_URL",
    ):
        require(nacos, value, "platform-operations-service.yaml")
    require(app, "PLATFORM_OPERATIONS_MEMORY_V2_COMPRESSION_ENABLED", "platform-operations application.yml")
    for value in (
        "PLATFORM_OPERATIONS_DB_USER", "PLATFORM_OPERATIONS_DB_PASSWORD",
        "PLATFORM_OPERATIONS_SOURCE_DB_USER", "PLATFORM_OPERATIONS_SOURCE_DB_PASSWORD",
        "MYSQL_DB_PLATFORM_OPERATIONS",
    ):
        require(nacos, value, "platform-operations datasource template")
    require(nacos, "  review:\n", "Platform Operations review configuration")
    require(nacos, "    sources:\n      mode:", "Platform Operations review source configuration")
    require(nacos, "  review:\n    authorization:", "Platform Operations review authorization")
    if "business-review:" in nacos or "BUSINESS_REVIEW_" in nacos or "knowledge-base-url:" in nacos:
        raise SystemExit("Platform Operations Nacos template retains a retired service config key")
    for line in nacos.splitlines():
        if re.match(r"^\s*(?:password|api-key):", line) and "${" not in line:
            raise SystemExit(f"Platform Operations Nacos template contains a non-placeholder secret: {line.strip()}")

    require(memory, "platform-operations-service-current: ${PLATFORM_OPERATIONS_SERVICE_IDENTITY_PUBLIC_KEY:}",
            "Memory trusted caller")
    for domain, identity in (("analytics", "platform-operations-analytics"),
                             ("review", "platform-operations-review"),
                             ("knowledge", "platform-operations-knowledge")):
        require(memory, f"{domain}: [{identity}]", "Memory isolated capability identities")
    for retired in ("compare-engine-service", "compare-engine-agent", "memory.v2.*.compare"):
        if retired in rag or retired in memory:
            raise SystemExit(f"RAG/Memory config still includes a retired Compare identity or space: {retired}")
    for retired in ("memory-domain-id: trade", "memory-domain-id: business-review",
                    "memory.v2.session.trade.platform-operations-analytics",
                    "memory.v2.session.business-review.platform-operations-review",
                    "business_review_platform_operations_review"):
        if retired in rag:
            raise SystemExit(f"RAG config retains a pre-consolidation platform memory namespace: {retired}")
    require(agent, '"[a2a:platform-operations-agent]": agent.chat.use', "Agent platform entry permission")
    require(agent, "service-id: platform-operations-service", "Agent platform endpoint identity")
    require(gateway, "- Path=/platform-operations/**", "Gateway platform route")
    require(gateway, "platform-operations-service: platform-operations-service", "Gateway trusted audience")
    require(publish, '"platform-operations-service.yaml"', "Nacos publication allowlist")
    active_nacos_ids = {p.name for p in (ROOT / "nacos").glob("*.yaml") if p.name != "mysql-common.yaml"}
    allowlist = re.search(r"ALLOWED = frozenset\(\{(.*?)\}\)", publish, re.DOTALL)
    published_ids = set(re.findall(r'"([a-z0-9-]+\.yaml)"', allowlist.group(1))) if allowlist else set()
    if published_ids != active_nacos_ids:
        raise SystemExit(f"Nacos publisher allowlist differs from active templates: missing={sorted(active_nacos_ids - published_ids)}, extra={sorted(published_ids - active_nacos_ids)}")
    require(init, 'notification-service.yaml platform-operations-service.yaml"', "full profile template requirements")
    require(start, "minimal|full|mcp|rag|platform-preview", "start profile choices")
    require(stop, "minimal|full|mcp|platform-preview", "stop profile choices")
    require(init, "fill_secret PLATFORM_OPERATIONS_DB_PASSWORD", "deployment credential bootstrap")
    require(init, "fill_secret PLATFORM_OPERATIONS_SOURCE_DB_PASSWORD", "deployment credential bootstrap")
    if any(retired in publish or retired in keygen or retired in init
           for retired in ("business-service.yaml", "business-review-service.yaml", "knowledge-service.yaml")):
        raise SystemExit("deployment scripts still accept a retired service template or identity")
    require(keygen, '"platform-operations-service"', "service identity generator")
    for retired in ("business-review-service", "business-service", "compare-engine-service",
                    "contract-service", "knowledge-service", "settlement-service"):
        if f'"{retired}"' in keygen:
            raise SystemExit(f"service identity generator still registers retired service: {retired}")

    for legacy_id in ("business-service.yaml", "business-review-service.yaml", "knowledge-service.yaml",
                      "compare-engine-service.yaml", "contract-service.yaml", "settlement-service.yaml",
                      "promotion-service.yaml"):
        require(retire, f'"{legacy_id}"', "allowlisted Nacos retirement script")
    require(retire, '"NACOS_RETIRE_DRY_RUN", "true"', "Nacos retirement dry-run default")
    require(retire, '"DELETE"', "Nacos retirement HTTP method")

    nacos_ids = re.search(r"(?m)^NACOS_CONFIG_DATA_IDS=([^\r\n]*)$", env)
    if not nacos_ids or nacos_ids.group(1):
        raise SystemExit(".env.example must leave NACOS_CONFIG_DATA_IDS empty for explicit publication")
    databases = re.search(r"(?m)^MYSQL_MCP_DATABASES=([^\r\n]*)$", env)
    if not databases:
        raise SystemExit(".env.example has no default MySQL MCP database allowlist")
    allowed_databases = set(databases.group(1).split(","))
    if "bk_platform_operations" not in allowed_databases:
        raise SystemExit("MySQL MCP allowlist must include the active Platform Operations database")
    if allowed_databases.intersection({"bk_business", "bk_business_review", "bk_knowledge", "bk_promotion"}):
        raise SystemExit("MySQL MCP allowlist still exposes a retired business database")

    schema = read("sql/migrations/20261029_platform_operations_schema.sql")
    schema_check = read("docker/mysql/checks/platform-operations-schema.sql")
    require(schema, "knowledge_interview_public_source_v1", "Platform Operations source view")
    require(schema, "GRANT SELECT ON bk_platform_operations.knowledge_interview_public_source_v1 TO 'platform_operations_source_ro'@'%';",
            "restricted source account grant")
    require(schema_check, "'a2a_task_execution_reservation'))=21", "Platform Operations schema check")

    view = read("sql/migrations/20261029_platform_operations_view_owner.sql")
    source = read("platform-operations-service/src/main/java/com/bkanent/knowledge/source/InterviewTranscriptSource.java")
    source_properties = read("platform-operations-service/src/main/java/com/bkanent/knowledge/config/KnowledgeSourceProperties.java")
    interview_mapper = read("interview-service/src/main/java/com/bkanent/interview/mapper/InterviewAssetMapper.java")
    interview_service = read("interview-service/src/main/java/com/bkanent/interview/service/InterviewAssetService.java")
    archive_grader = read("interview-service/src/main/java/com/bkanent/interview/engine/ArchiveGrader.java")
    sanitizer = read("interview-service/src/main/java/com/bkanent/interview/engine/Sanitizer.java")
    for predicate in (
        "a.visibility='PUBLISHED'", "a.archive_grade IN ('L2','L3')", "a.sanitization_revision>=1",
        "a.deleted=0", "c.deleted=0", "SHA2(a.content,256)=a.content_hash",
        "s.case_id=a.case_id AND s.status='ARCHIVED'", "a.source='UPLOAD'",
        "DEFINER='platform_operations_view_owner'@'%'",
    ):
        require(view, predicate, "restricted Interview source view")
    if re.search(r"(?i)\braw_content\b", view):
        raise SystemExit("Platform Operations Interview view must not read raw_content")
    for predicate in (
        "a.visibility = 'PUBLISHED'", "a.archive_grade IN ('L2', 'L3')", "a.deleted = 0",
        "c.deleted = 0", "SHA2(a.content, 256) = a.content_hash",
        "s.case_id = a.case_id AND s.status = 'ARCHIVED'", "a.source = 'UPLOAD'",
    ):
        require(interview_mapper, predicate, "Interview public-source mapper")
    require(archive_grader, 'return "L2".equals(grade) || "L3".equals(grade);', "Interview archive grade gate")
    require(interview_service, "Sanitizer.containsCredentialShape(asset.getTitle())", "Interview source eligibility")
    require(interview_service, "Sanitizer.containsCredentialShape(asset.getContent())", "Interview source eligibility")
    require(source, "TranscriptIntegrity.containsCredentialShape(title)", "Platform Operations source validation")
    require(source, "TranscriptIntegrity.containsCredentialShape(content)", "Platform Operations source validation")
    if "supportedSanitizationRevision != 1" not in source_properties:
        raise SystemExit("Platform Operations source admission must pin the sanitizer revision")

    print("OK: consolidated service, Compose, Nacos, Auth/Memory/RAG identities and source-view gates are synchronized")


if __name__ == "__main__":
    main()
