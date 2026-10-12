-- MySQL 8.4; repeatable additive memory-only migration. Never execute against another domain.
USE bk_memory;

-- Every active preference/constraint writer and backfill locks this row before reading source/revision.
CREATE TABLE IF NOT EXISTS memory_rag_lock (
    lock_id INT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
INSERT IGNORE INTO memory_rag_lock(lock_id) VALUES (1);

-- Retain deletion identities/revisions throughout migration and replay windows.
CREATE TABLE IF NOT EXISTS memory_rag_projection (
    space_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    document_id VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    tombstone BOOLEAN NOT NULL,
    event_payload LONGTEXT NOT NULL,
    PRIMARY KEY (space_id, document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Sensitive complete projections: restrict this table to the memory runtime DB account.
-- No access token, credential or authentication envelope is persisted here.
-- Purge acknowledged payloads only after the operator-approved migration/replay retention window;
-- projection tombstones MUST remain. Backfill always regenerates from locked domain truth.
CREATE TABLE IF NOT EXISTS memory_rag_outbox (
    event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    space_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    document_id VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    event_payload LONGTEXT NOT NULL,
    state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    error_code VARCHAR(64) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_memory_rag_revision(space_id, document_id, revision),
    KEY idx_memory_rag_dispatch(state, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
