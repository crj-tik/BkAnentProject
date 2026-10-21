-- Versioned private experience records and evidence locators for Memory v2.
-- Active payload retention is controlled by retention_until and the owning policy.
-- A NULL retention_until means retain until an explicit lifecycle action. Deletion clears
-- payload/evidence details but keeps revision, source hashes and IDs as anti-resurrection tombstones.
USE bk_memory;

CREATE TABLE IF NOT EXISTS memory_experience (
    owner_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    domain_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    agent_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    experience_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    schema_version VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    lifecycle_revision BIGINT NOT NULL DEFAULT 1,
    state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
    confidence DECIMAL(8,7) NULL,
    activation DECIMAL(8,7) NULL,
    last_observed_at DATETIME(6) NULL,
    last_validated_at DATETIME(6) NULL,
    retention_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (owner_id, domain_id, agent_id, experience_id),
    KEY idx_memory_experience_retention (state, retention_until, experience_id),
    CHECK (revision > 0 AND lifecycle_revision > 0),
    CHECK (state IN ('ACTIVE', 'INACTIVE', 'WITHDRAWN', 'DELETION_PENDING', 'DELETED')),
    CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1),
    CHECK (activation IS NULL OR activation BETWEEN 0 AND 1),
    CHECK (state = 'DELETED' OR confidence IS NOT NULL AND activation IS NOT NULL AND last_observed_at IS NOT NULL),
    CHECK (last_validated_at IS NULL OR last_observed_at IS NOT NULL AND last_validated_at <= last_observed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- The compound primary key also covers the scoped active-candidate lookup and its ordering.
-- Drop the pre-release duplicate index if this script was already applied during development.
SELECT COUNT(*) INTO @memory_experience_scope_index_count
FROM information_schema.statistics
WHERE table_schema = DATABASE() AND table_name = 'memory_experience' AND index_name = 'idx_memory_experience_scope';
SET @memory_experience_scope_index_sql = IF(@memory_experience_scope_index_count > 0,
    'ALTER TABLE memory_experience DROP INDEX idx_memory_experience_scope', 'SELECT 1');
PREPARE memory_experience_scope_index_stmt FROM @memory_experience_scope_index_sql;
EXECUTE memory_experience_scope_index_stmt;
DEALLOCATE PREPARE memory_experience_scope_index_stmt;

-- Immutable content versions; payload_json is nulled by an authorized delete, hashes remain tombstones.
CREATE TABLE IF NOT EXISTS memory_experience_revision (
    owner_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    domain_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    agent_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    experience_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    schema_version VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_json LONGTEXT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (owner_id, domain_id, agent_id, experience_id, revision),
    KEY idx_memory_experience_revision_hash (experience_id, content_hash),
    CHECK (revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Evidence records keep outcome/feedback and occurred/observed time distinct.
-- DELETED rows retain only their stable identity and lifecycle state.
CREATE TABLE IF NOT EXISTS memory_experience_evidence (
    owner_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    domain_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    agent_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    experience_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    experience_revision BIGINT NOT NULL,
    evidence_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    outcome VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    feedback VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    verified BOOLEAN NULL,
    occurred_at DATETIME(6) NULL,
    observed_at DATETIME(6) NULL,
    state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
    PRIMARY KEY (owner_id, domain_id, agent_id, experience_id, experience_revision, evidence_id),
    KEY idx_memory_experience_evidence_version (owner_id, domain_id, agent_id, experience_id, experience_revision),
    CHECK (experience_revision > 0),
    CHECK (state IN ('ACTIVE', 'WITHDRAWN', 'DELETED')),
    CHECK (state = 'DELETED' OR (
        evidence_kind IN ('BUSINESS_OUTCOME', 'USER_FEEDBACK', 'MODEL_SELF_ASSESSMENT', 'ACCEPTANCE')
        AND outcome IN ('SUCCEEDED', 'FAILED', 'PARTIAL', 'UNKNOWN', 'ACCEPTED')
        AND feedback IN ('POSITIVE', 'NEGATIVE', 'NEUTRAL', 'NONE')
        AND verified IS NOT NULL AND occurred_at IS NOT NULL AND observed_at IS NOT NULL
    )),
    CHECK (state = 'DELETED' OR evidence_kind <> 'ACCEPTANCE' OR outcome = 'ACCEPTED'),
    CHECK (state = 'DELETED' OR verified = FALSE OR (
        evidence_kind = 'BUSINESS_OUTCOME' AND outcome NOT IN ('UNKNOWN', 'ACCEPTED')
        OR evidence_kind = 'USER_FEEDBACK' AND feedback <> 'NONE'
    ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Locator-only dependencies let revocation/deletion find exact derivatives without copying source bodies.
CREATE TABLE IF NOT EXISTS memory_experience_evidence_source (
    source_link_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    owner_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    domain_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    agent_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    experience_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    experience_revision BIGINT NOT NULL,
    evidence_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_context_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_event_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
    source_sequence BIGINT NULL,
    source_revision BIGINT NOT NULL,
    source_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_schema_version VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    grant_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
    grant_revision BIGINT NULL,
    state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_memory_experience_source (owner_id, domain_id, agent_id, experience_id, experience_revision, evidence_id, source_link_hash),
    KEY idx_memory_experience_source_target (owner_id, domain_id, agent_id, experience_id, experience_revision, evidence_id),
    KEY idx_memory_experience_source_event (source_context_id, source_event_id, state, experience_id),
    CHECK (experience_revision > 0 AND source_revision > 0),
    CHECK (source_sequence IS NULL OR source_sequence > 0),
    CHECK ((grant_id IS NULL AND grant_revision IS NULL) OR (grant_id IS NOT NULL AND grant_revision > 0)),
    CHECK (source_kind IN ('MESSAGE', 'DOMAIN_RECORD', 'GENERATED_ARTIFACT', 'MANUAL_REVISION', 'SUMMARY', 'SHARED_EXPORT', 'FEEDBACK', 'OUTCOME')),
    CHECK (state IN ('ACTIVE', 'WITHDRAWN', 'DELETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
