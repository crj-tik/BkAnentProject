-- Additive runtime receipts for immutable shared export publishing and per-recipient index acknowledgements.
USE bk_memory;

CREATE TABLE IF NOT EXISTS memory_shared_export_lock (
    lock_id TINYINT NOT NULL PRIMARY KEY,
    CHECK (lock_id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO memory_shared_export_lock(lock_id) VALUES (1)
ON DUPLICATE KEY UPDATE lock_id=VALUES(lock_id);

CREATE TABLE IF NOT EXISTS memory_shared_export_runtime (
    export_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_revision BIGINT NOT NULL,
    source_invocation_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    submit_request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    index_state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'NOT_REQUESTED',
    last_error VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (export_id, content_revision),
    UNIQUE KEY uk_memory_export_invocation (export_id, source_invocation_id),
    CHECK (content_revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS memory_shared_export_projection (
    export_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_revision BIGINT NOT NULL,
    recipient_domain_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recipient_agent_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recipient_conversation_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    grant_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    space_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    document_id VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    index_state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'INDEX_PENDING',
    PRIMARY KEY (export_id, content_revision, recipient_domain_id, recipient_agent_id, recipient_conversation_id),
    UNIQUE KEY uk_memory_export_projection_event (event_id),
    KEY idx_memory_export_projection_scope (recipient_domain_id, recipient_agent_id, recipient_conversation_id, grant_id, index_state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
