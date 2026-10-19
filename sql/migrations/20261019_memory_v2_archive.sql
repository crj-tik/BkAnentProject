-- Memory v2 archive. Additive and repeatable; v1 data and applied migrations stay intact.
USE bk_memory;

CREATE TABLE IF NOT EXISTS memory_conversation (
    context_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    owner_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    domain_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    agent_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    conversation_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    last_sequence BIGINT NOT NULL DEFAULT 0,
    covered_through BIGINT NOT NULL DEFAULT 0,
    policy_version VARCHAR(64) NOT NULL DEFAULT 'archive-v2.1',
    UNIQUE KEY uk_memory_context (owner_id, domain_id, agent_id, conversation_id),
    CHECK (revision >= 0 AND last_sequence >= covered_through AND covered_through >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS memory_message (
    context_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sequence_no BIGINT NOT NULL,
    message_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_event_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_revision BIGINT NOT NULL,
    schema_version VARCHAR(255) NOT NULL,
    source_caller VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    payload_json LONGTEXT NULL,
    PRIMARY KEY (context_id, sequence_no),
    UNIQUE KEY uk_memory_message_event (context_id, source_event_id),
    UNIQUE KEY uk_memory_message_id (context_id, message_id),
    CHECK (sequence_no > 0 AND content_revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS memory_receive_permit (
    permit_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    context_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_caller VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    body_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_memory_permit_request (context_id, source_caller, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS memory_receive_event (
    permit_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    purpose VARCHAR(255) NOT NULL,
    admission_json LONGTEXT NULL,
    receipt_json LONGTEXT NULL,
    PRIMARY KEY (permit_id, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS memory_summary (
    context_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    summary_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_revision BIGINT NOT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    covered_from BIGINT NOT NULL,
    covered_through BIGINT NOT NULL,
    state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    payload_json LONGTEXT NULL,
    receipt_json LONGTEXT NOT NULL,
    PRIMARY KEY (context_id, summary_id, content_revision),
    KEY idx_memory_summary_current (context_id, state, covered_through)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- One immutable result identity per accepted invocation, even across permit retries.
CREATE TABLE IF NOT EXISTS memory_result_admission (
    admission_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    admission_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    context_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_event_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    UNIQUE KEY uk_memory_result_event (context_id, result_event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS memory_entity_anchor (
    context_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    entity_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_revision BIGINT NOT NULL,
    summary_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    summary_revision BIGINT NOT NULL,
    state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    payload_json LONGTEXT NULL,
    PRIMARY KEY (context_id, entity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS memory_matter (
    context_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    matter_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_revision BIGINT NOT NULL,
    state VARCHAR(32) NOT NULL,
    payload_json LONGTEXT NULL,
    PRIMARY KEY (context_id, matter_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Dependency rows contain locators only; a stale checkpoint must recheck source/grant truth.
CREATE TABLE IF NOT EXISTS memory_source_dependency (
    target_context_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_kind VARCHAR(32) NOT NULL,
    target_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_revision BIGINT NOT NULL,
    source_context_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_event_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    dependency_json LONGTEXT NOT NULL,
    KEY idx_memory_dependency_target (target_context_id, target_kind, target_id, target_revision),
    KEY idx_memory_dependency_source (source_context_id, source_event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Reliable handoff only. No model/embedding worker is enabled by this migration.
CREATE TABLE IF NOT EXISTS memory_processing_job (
    job_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    context_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_event_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    job_kind VARCHAR(32) NOT NULL,
    state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    available_at DATETIME(6) NOT NULL,
    lease_owner VARCHAR(64) NULL,
    lease_until DATETIME(6) NULL,
    last_error VARCHAR(64) NULL,
    KEY idx_memory_job_claim (job_kind, state, available_at, lease_until),
    KEY idx_memory_job_source (context_id, source_event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
