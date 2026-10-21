-- Independently governed domain generalizations are stored outside private experiences.
-- No owner, private experience ID, source locator, or grant is accepted by this schema.
USE bk_memory;

CREATE TABLE IF NOT EXISTS memory_domain_experience (
    domain_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    agent_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    capability_tag VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    payload_json LONGTEXT NULL,
    governance_approval_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
    policy_version VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
    source_review_ref VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
    purpose VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
    independent_non_personal BOOLEAN NOT NULL DEFAULT FALSE,
    retention_authorized BOOLEAN NOT NULL DEFAULT FALSE,
    valid_until DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (domain_id, agent_id, aggregate_id),
    KEY idx_memory_domain_experience_search (domain_id, agent_id, state, aggregate_id),
    KEY idx_memory_domain_experience_capability (domain_id, agent_id, state, capability_tag, aggregate_id),
    CHECK (revision > 0),
    CHECK (state IN ('ACTIVE', 'WITHDRAWN')),
    CHECK ((state = 'ACTIVE' AND content_hash IS NOT NULL AND payload_json IS NOT NULL
            AND governance_approval_id IS NOT NULL AND policy_version IS NOT NULL
            AND source_review_ref IS NOT NULL AND purpose IS NOT NULL AND independent_non_personal = TRUE
            AND retention_authorized = TRUE
            AND valid_until IS NOT NULL)
        OR (state = 'WITHDRAWN' AND content_hash IS NULL AND payload_json IS NULL
            AND governance_approval_id IS NULL AND policy_version IS NULL AND source_review_ref IS NULL
            AND purpose IS NULL AND independent_non_personal = FALSE AND retention_authorized = FALSE AND valid_until IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Governance audit versions retain only the independently approved derivative and opaque review decision.
CREATE TABLE IF NOT EXISTS memory_domain_experience_revision (
    domain_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    agent_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    governance_approval_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
    policy_version VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
    source_review_ref VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
    purpose VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
    independent_non_personal BOOLEAN NOT NULL DEFAULT FALSE,
    retention_authorized BOOLEAN NOT NULL DEFAULT FALSE,
    payload_json LONGTEXT NULL,
    withdrawal_reason VARCHAR(1000) NULL,
    changed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (domain_id, agent_id, aggregate_id, revision),
    CHECK (revision > 0),
    CHECK (state IN ('ACTIVE', 'WITHDRAWN')),
    CHECK ((state = 'ACTIVE' AND independent_non_personal = TRUE AND retention_authorized = TRUE)
        OR (state = 'WITHDRAWN' AND independent_non_personal = FALSE AND retention_authorized = FALSE))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Request receipts make approval retries idempotent without storing a private source reference.
CREATE TABLE IF NOT EXISTS memory_domain_experience_event (
    caller_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    domain_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    agent_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    response_json LONGTEXT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (caller_id, request_id),
    KEY idx_memory_domain_experience_event_target (domain_id, agent_id, aggregate_id),
    CHECK (revision IS NULL OR revision > 0),
    CHECK (state IS NULL OR state IN ('PUBLISHED', 'WITHDRAWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
