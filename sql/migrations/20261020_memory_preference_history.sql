-- Additive preference history; no legacy preference rows are imported or overwritten.
USE bk_memory;

CREATE TABLE IF NOT EXISTS memory_message_observation (
    context_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_event_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    accepted_at DATETIME(6) NOT NULL,
    PRIMARY KEY (context_id, source_event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS memory_preference_block (
    block_id VARCHAR(66) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    domain_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    agent_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    subject_ref VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    dimension_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    authorization_revision BIGINT NOT NULL DEFAULT 1,
    last_ordinal BIGINT NOT NULL DEFAULT 0,
    state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    UNIQUE KEY uk_memory_preference_key (key_hash),
    KEY idx_memory_preference_owner (owner_id, domain_id, agent_id, block_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS memory_preference_revision (
    block_id VARCHAR(66) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    snapshot_json LONGTEXT NULL,
    PRIMARY KEY (block_id, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS memory_preference_transition (
    block_id VARCHAR(66) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    transition_id VARCHAR(66) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    history_ordinal BIGINT NOT NULL,
    introduced_revision BIGINT NOT NULL,
    source_context_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_event_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    candidate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    transition_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_json LONGTEXT NOT NULL,
    payload_json LONGTEXT NULL,
    PRIMARY KEY (block_id, transition_id),
    UNIQUE KEY uk_memory_preference_ordinal (block_id, history_ordinal),
    UNIQUE KEY uk_memory_preference_evidence (block_id, source_context_id, source_event_id),
    KEY idx_memory_preference_history (block_id, introduced_revision, history_ordinal),
    KEY idx_memory_preference_source (source_context_id, source_event_id, block_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
