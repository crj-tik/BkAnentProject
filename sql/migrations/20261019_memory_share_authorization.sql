-- MySQL 8.4; current grants and immutable shared export versions. No user credentials.
USE bk_memory;

CREATE TABLE IF NOT EXISTS memory_share_grant (
    grant_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    grant_revision BIGINT NOT NULL,
    owner_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_domain_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_agent_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_conversation_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    shared_scope_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    matter_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
    purpose VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    valid_from DATETIME(6) NOT NULL,
    valid_until DATETIME(6) NOT NULL,
    grant_payload LONGTEXT NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_memory_grant_owner_scope (owner_id, scope_kind, shared_scope_id),
    CHECK (grant_revision > 0),
    CHECK (valid_until > valid_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Future grant writers must append this snapshot in the same transaction as the current revision.
CREATE TABLE IF NOT EXISTS memory_share_grant_history (
    grant_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    grant_revision BIGINT NOT NULL,
    grant_payload LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (grant_id, grant_revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Each content version is immutable; lifecycle state may only move through managed writers.
-- Details use this SQL truth even while RAG is pending. Sensitive payloads remain memory-runtime-only.
CREATE TABLE IF NOT EXISTS memory_shared_export (
    export_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_revision BIGINT NOT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    schema_version VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_domain_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_agent_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_conversation_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    grant_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    grant_revision BIGINT NOT NULL,
    matter_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
    purpose VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    lifecycle_revision BIGINT NOT NULL DEFAULT 1,
    payload_json LONGTEXT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (export_id, content_revision),
    KEY idx_memory_export_grant (grant_id, grant_revision, state),
    KEY idx_memory_export_source (owner_id, source_domain_id, source_agent_id, source_conversation_id),
    CHECK (content_revision > 0 AND grant_revision > 0 AND lifecycle_revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
