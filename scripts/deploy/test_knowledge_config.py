#!/usr/bin/env python3
"""Static cross-file checks for the independent Knowledge service configuration."""

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
    app = read("knowledge-service/src/main/resources/application.yml")
    nacos = read("nacos/knowledge-service.yaml")
    memory = read("nacos/memory-service.yaml")
    agent = read("nacos/agent-service.yaml")
    gateway = read("nacos/gateway.yaml")
    publish = read("scripts/deploy/publish-nacos.py")
    init = read("scripts/deploy/init-environment.sh")
    keygen = read("scripts/security/ServiceIdentityKeygen.java")

    service_vars = (
        "MYSQL_DB_KNOWLEDGE",
        "KNOWLEDGE_PRIMARY_DB_URL",
        "KNOWLEDGE_MYSQL_USER",
        "KNOWLEDGE_MYSQL_PASSWORD",
        "KNOWLEDGE_SOURCE_DB_URL",
        "KNOWLEDGE_SOURCE_DB_USER",
        "KNOWLEDGE_SOURCE_DB_PASSWORD",
        "KNOWLEDGE_MEMORY_V2_COMPRESSION_ENABLED",
        "KNOWLEDGE_SERVICE_BASE_URL",
    )
    for key in service_vars:
        require(env, key, ".env.example")
        require(compose, key, "docker-compose.yml")
    for key in ("KNOWLEDGE_PRIMARY_DB_URL", "KNOWLEDGE_MYSQL_USER", "KNOWLEDGE_MYSQL_PASSWORD",
                "KNOWLEDGE_SOURCE_DB_URL", "KNOWLEDGE_SOURCE_DB_USER", "KNOWLEDGE_SOURCE_DB_PASSWORD"):
        require(app, key, "application.yml")
        require(nacos, key, "knowledge-service.yaml")
    for config in (app, nacos):
        for setting in (
            "compression-enabled: ${KNOWLEDGE_MEMORY_V2_COMPRESSION_ENABLED:false}",
            "key-id: ${SERVICE_IDENTITY_KEY_ID:knowledge-service-current}",
            "private-key-file: ${SERVICE_IDENTITY_PRIVATE_KEY_FILE:/run/secrets/service-identity.pem}",
            "response-public-key: ${AUTH_SERVICE_IDENTITY_PUBLIC_KEY:}",
            "required-dependencies: ${SERVICE_READINESS_REQUIRED_DEPENDENCIES:nacos,database,memory}",
            "address: ${DUBBO_REGISTRY_ADDRESS:nacos://${NACOS_SERVER:",
        ):
            require(config, setting, "Knowledge application/Nacos Memory and Dubbo settings")

    compose_service = re.search(r"(?ms)^  knowledge-service:\n(.*?)(?=^  [a-zA-Z0-9_-]+:\n|^volumes:)", compose)
    if not compose_service:
        raise SystemExit("docker-compose.yml has no independent knowledge-service block")
    block = compose_service.group(1)
    for value in ("MAVEN_MODULES: knowledge-service", "SERVICE_ARTIFACT: knowledge-service-1.0.0-SNAPSHOT.jar",
                  "MYSQL_USER: ${KNOWLEDGE_MYSQL_USER", "MYSQL_PASSWORD: ${KNOWLEDGE_MYSQL_PASSWORD",
                  "source: ./.secrets/service-identities/knowledge-service/private-key.pem",
                  "SERVICE_READINESS_REQUIRED_DEPENDENCIES: nacos,database,memory"):
        require(block, value, "docker-compose.yml knowledge-service")
    for key in ("KNOWLEDGE_PRIMARY_DB_URL", "KNOWLEDGE_SOURCE_DB_URL"):
        require(block, f'{key}: "${{{key}:-jdbc:mysql://mysql:3306/${{MYSQL_DB_KNOWLEDGE:-bk_knowledge}}?',
                "docker-compose.yml Knowledge datasource defaults")
    if "MYSQL_ROOT_PASSWORD" in block:
        raise SystemExit("knowledge-service must not receive the MySQL root password")

    for line in nacos.splitlines():
        if re.match(r"^\s*(?:password|api-key):", line) and "${" not in line:
            raise SystemExit(f"Nacos Knowledge template contains a non-placeholder secret: {line.strip()}")
    require(app, "server:\n  port: 9015", "knowledge-service application.yml")
    require(memory, "knowledge: [knowledge-agent]", "nacos/memory-service.yaml")
    require(memory, "knowledge-service-current: ${KNOWLEDGE_SERVICE_IDENTITY_PUBLIC_KEY:}", "nacos/memory-service.yaml")
    require(agent, '"[a2a:knowledge-agent]": agent.workflow.invoke.knowledge', "nacos/agent-service.yaml")
    require(agent, "service-id: knowledge-service", "nacos/agent-service.yaml")
    require(gateway, "- Path=/knowledge/**", "nacos/gateway.yaml")
    require(gateway, "knowledge-service: knowledge-service", "nacos/gateway.yaml")
    require(publish, '"knowledge-service.yaml"', "publish-nacos.py")
    require(init, "knowledge-service.yaml", "init-environment.sh")
    require(init, "fill_secret KNOWLEDGE_MYSQL_PASSWORD", "init-environment.sh")
    require(init, "fill_secret KNOWLEDGE_SOURCE_DB_PASSWORD", "init-environment.sh")
    require(keygen, '"knowledge-service"', "ServiceIdentityKeygen.java")
    require(keygen, 'SERVICES.size() + " local service identity pairs', "ServiceIdentityKeygen.java")
    identity_services = re.search(r"(?s)SERVICES\s*=\s*List\.of\((.*?)\);", keygen)
    registered_services = set(re.findall(r'"([^"\\]+)"', identity_services.group(1))) if identity_services else set()
    for service in ("business-review-service", "knowledge-service"):
        if service not in registered_services:
            raise SystemExit(f"ServiceIdentityKeygen.java does not register {service}")

    nacos_ids = re.search(r"(?m)^NACOS_CONFIG_DATA_IDS=([^\r\n]*)$", env)
    if not nacos_ids or nacos_ids.group(1):
        raise SystemExit(".env.example must leave NACOS_CONFIG_DATA_IDS empty for explicit publication")
    databases = re.search(r"(?m)^MYSQL_MCP_DATABASES=([^\r\n]*)$", env)
    if not databases:
        raise SystemExit(".env.example has no default MySQL MCP database allowlist")
    allowed_databases = set(databases.group(1).split(","))
    if not {"bk_interview", "bk_business_review", "bk_knowledge"}.issubset(allowed_databases):
        raise SystemExit(".env.example MySQL MCP allowlist omits an active business database")
    if "bk_promotion" in allowed_databases:
        raise SystemExit(".env.example MySQL MCP allowlist must exclude legacy bk_promotion")

    view = read("sql/migrations/20261027_knowledge_domain.sql")
    interview_mapper = read("interview-service/src/main/java/com/bkanent/interview/mapper/InterviewAssetMapper.java")
    interview_service = read("interview-service/src/main/java/com/bkanent/interview/service/InterviewAssetService.java")
    archive_grader = read("interview-service/src/main/java/com/bkanent/interview/engine/ArchiveGrader.java")
    sanitizer = read("interview-service/src/main/java/com/bkanent/interview/engine/Sanitizer.java")
    source_adapter = read("knowledge-service/src/main/java/com/bkanent/knowledge/source/InterviewTranscriptSource.java")
    source_properties = read("knowledge-service/src/main/java/com/bkanent/knowledge/config/KnowledgeSourceProperties.java")
    for predicate in (
        "a.visibility='PUBLISHED'", "a.archive_grade IN ('L2','L3')", "a.sanitization_revision>=1",
        "a.deleted=0", "c.deleted=0", "SHA2(a.content,256)=a.content_hash",
        "s.case_id=a.case_id AND s.status='ARCHIVED'", "a.source='UPLOAD'",
    ):
        require(view, predicate, "knowledge Interview source view")
    if re.search(r"(?i)\braw_content\b", view):
        raise SystemExit("Knowledge Interview source view migration must not read raw_content")
    for predicate in ("a.visibility = 'PUBLISHED'", "a.archive_grade IN ('L2', 'L3')",
                      "a.deleted = 0", "c.deleted = 0", "SHA2(a.content, 256) = a.content_hash",
                      "s.case_id = a.case_id AND s.status = 'ARCHIVED'", "a.source = 'UPLOAD'"):
        require(interview_mapper, predicate, "Interview public-source mapper")
    require(archive_grader, 'return "L2".equals(grade) || "L3".equals(grade);', "Interview archive grade gate")
    require(interview_service, "Sanitizer.containsCredentialShape(asset.getTitle())", "Interview public eligibility")
    require(interview_service, "Sanitizer.containsCredentialShape(asset.getContent())", "Interview public eligibility")
    require(source_adapter, "TranscriptIntegrity.containsCredentialShape(title)", "Knowledge source validation")
    require(source_adapter, "TranscriptIntegrity.containsCredentialShape(content)", "Knowledge source validation")
    if "public static final int VERSION = 1;" not in sanitizer or "supportedSanitizationRevision != 1" not in source_properties:
        raise SystemExit("Knowledge source admission requires an explicitly synchronized sanitizer version")

    print("OK: Knowledge environment, Compose, Nacos, identity, Auth/Memory routes and source-view gates are synchronized")


if __name__ == "__main__":
    main()
