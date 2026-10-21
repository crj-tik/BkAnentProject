-- Durable idempotent intake receipts for trusted outcome events and owner feedback.
-- The inbox stores only the bounded submitted event and immutable source locator; it does not copy domain records.
USE bk_memory;

CREATE TABLE IF NOT EXISTS memory_experience_event (
    context_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    domain_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    agent_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    experience_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expected_revision BIGINT NOT NULL,
    event_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_caller VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_json LONGTEXT NULL,
    source_context_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_event_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_revision BIGINT NOT NULL,
    source_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_schema_version VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
    occurred_at DATETIME(6) NOT NULL,
    accepted_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (context_id, evidence_id),
    UNIQUE KEY uk_memory_experience_event_identity (owner_id, domain_id, agent_id, evidence_id),
    KEY idx_memory_experience_event_source (source_context_id, source_event_id, source_state),
    KEY idx_memory_experience_event_experience (owner_id, domain_id, agent_id, experience_id),
    CHECK (expected_revision >= 0 AND source_revision > 0),
    CHECK (event_kind IN ('BUSINESS_OUTCOME', 'USER_FEEDBACK')),
    CHECK (source_state IN ('ACTIVE', 'WITHDRAWN', 'DELETED')),
    CHECK (source_kind IN ('MESSAGE', 'DOMAIN_RECORD', 'OUTCOME'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Same-matter selection is a cache only; every hit is revalidated against the current experience revision and source grants.
CREATE TABLE IF NOT EXISTS memory_experience_selection (
    context_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    matter_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    matter_revision BIGINT NOT NULL,
    objective_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scoring_version VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    token_budget INT NOT NULL,
    selection_json LONGTEXT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (context_id, matter_id),
    CHECK (matter_revision > 0 AND token_budget BETWEEN 1 AND 4096)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
