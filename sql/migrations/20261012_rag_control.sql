-- RAG control facts only; deliberately self-selects its database (KI-37/38).
-- MySQL 8.4: repeatable CREATE IF NOT EXISTS, no unsupported ADD COLUMN IF NOT EXISTS.
-- Apply with a migration administrator. Runtime needs SELECT/INSERT/UPDATE only on
-- bk_rag.rag_document_state and bk_rag.rag_index_event, not DDL or other bk_* databases.
-- Payload contains potentially sensitive business text, NEVER authentication envelopes,
-- tokens, signing keys or backend credentials. Restrict DB/backups to RAG operators.
-- Retain latest payload (including tombstones) throughout the migration/replay window.
-- Superseded payloads may be purged only after an operator-approved retention window
-- (baseline 90 days, and never less than the agreed migration/replay window); retain
-- event identity/hash and tombstone control facts. No automatic destructive purge here.
CREATE DATABASE IF NOT EXISTS bk_rag CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
USE bk_rag;

CREATE TABLE IF NOT EXISTS rag_document_state (
    space_id VARCHAR(128) NOT NULL,
    document_id VARCHAR(512) NOT NULL,
    latest_revision BIGINT NOT NULL DEFAULT 0,
    latest_hash CHAR(64) NULL,
    latest_payload_hash CHAR(64) NULL,
    latest_event_id VARCHAR(128) NULL,
    schema_version VARCHAR(128) NULL,
    tombstone BOOLEAN NOT NULL DEFAULT FALSE,
    state VARCHAR(32) NOT NULL DEFAULT 'ACCEPTED',
    keyword_applied_revision BIGINT NOT NULL DEFAULT 0,
    vector_applied_revision BIGINT NOT NULL DEFAULT 0,
    keyword_attempts INT NOT NULL DEFAULT 0,
    vector_attempts INT NOT NULL DEFAULT 0,
    keyword_error VARCHAR(64) NULL,
    vector_error VARCHAR(64) NULL,
    keyword_next_retry_at TIMESTAMP(6) NULL,
    vector_next_retry_at TIMESTAMP(6) NULL,
    reconcile_at TIMESTAMP(6) NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (space_id, document_id),
    KEY ix_rag_document_work (state, updated_at),
    KEY ix_rag_document_reconcile (state, reconcile_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS rag_index_event (
    event_id VARCHAR(128) NOT NULL,
    space_id VARCHAR(128) NOT NULL,
    document_id VARCHAR(512) NOT NULL,
    revision BIGINT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    schema_version VARCHAR(128) NOT NULL,
    operation VARCHAR(16) NOT NULL,
    canonical_payload MEDIUMTEXT NULL,
    state VARCHAR(32) NOT NULL,
    error_code VARCHAR(64) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    received_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (event_id),
    KEY ix_rag_event_document (space_id, document_id, revision),
    KEY ix_rag_event_retention (received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
