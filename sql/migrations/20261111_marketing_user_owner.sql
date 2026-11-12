-- Add trusted Auth user ownership while retaining the broker business identity.
USE bk_marketing;

SET @owner_migration_columns = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema='bk_marketing' AND table_name='marketing_content' AND column_name='owner_user_id'
);
SET @owner_migration_sql = IF(@owner_migration_columns=0,
    'ALTER TABLE marketing_content ADD COLUMN owner_user_id BIGINT NULL AFTER owner_broker_id', 'SELECT 1');
PREPARE owner_migration_statement FROM @owner_migration_sql;
EXECUTE owner_migration_statement;
DEALLOCATE PREPARE owner_migration_statement;

SET @owner_migration_indexes = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema='bk_marketing' AND table_name='marketing_content'
      AND index_name='idx_marketing_content_owner_user'
);
SET @owner_migration_sql = IF(@owner_migration_indexes=0,
    'CREATE INDEX idx_marketing_content_owner_user ON marketing_content(owner_user_id, deleted, id)', 'SELECT 1');
PREPARE owner_migration_statement FROM @owner_migration_sql;
EXECUTE owner_migration_statement;
DEALLOCATE PREPARE owner_migration_statement;

CREATE TABLE IF NOT EXISTS marketing_owner_migration_ledger (
    record_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    record_id BIGINT NOT NULL,
    legacy_broker_id BIGINT NULL,
    candidate_user_id BIGINT NULL,
    evidence_reference VARCHAR(255) NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    verified_by_user_id BIGINT NULL,
    verified_at DATETIME NULL,
    applied_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (record_type, record_id),
    KEY idx_marketing_owner_ledger_status (status, record_type, record_id),
    KEY idx_marketing_owner_ledger_candidate (candidate_user_id, record_type, status),
    CONSTRAINT chk_marketing_owner_ledger_status CHECK (status IN ('PENDING','VERIFIED','APPLIED','REJECTED')),
    CONSTRAINT chk_marketing_owner_ledger_type CHECK (record_type IN ('CONTENT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
