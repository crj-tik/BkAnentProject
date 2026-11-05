-- Internal listing business model (implement-internal-listing-business task 2.2).
-- Incremental only: existing listing_info rows keep their values; new columns stay NULL/0 until backfilled.
USE bk_listing;

SET @owner_customer_column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = 'bk_listing' AND table_name = 'listing_info'
      AND column_name = 'owner_customer_id'
);
SET @owner_customer_column_sql = IF(@owner_customer_column_exists = 0,
    'ALTER TABLE listing_info ADD COLUMN owner_customer_id BIGINT NULL AFTER broker_id',
    'SELECT 1');
PREPARE owner_customer_column_statement FROM @owner_customer_column_sql;
EXECUTE owner_customer_column_statement;
DEALLOCATE PREPARE owner_customer_column_statement;

SET @community_column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = 'bk_listing' AND table_name = 'listing_info'
      AND column_name = 'community_name'
);
SET @community_column_sql = IF(@community_column_exists = 0,
    'ALTER TABLE listing_info ADD COLUMN community_name VARCHAR(128) NULL AFTER address',
    'SELECT 1');
PREPARE community_column_statement FROM @community_column_sql;
EXECUTE community_column_statement;
DEALLOCATE PREPARE community_column_statement;

SET @district_column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = 'bk_listing' AND table_name = 'listing_info'
      AND column_name = 'district_code'
);
SET @district_column_sql = IF(@district_column_exists = 0,
    'ALTER TABLE listing_info ADD COLUMN district_code VARCHAR(32) NULL AFTER community_name',
    'SELECT 1');
PREPARE district_column_statement FROM @district_column_sql;
EXECUTE district_column_statement;
DEALLOCATE PREPARE district_column_statement;

SET @asking_price_column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = 'bk_listing' AND table_name = 'listing_info'
      AND column_name = 'asking_price_yuan'
);
SET @asking_price_column_sql = IF(@asking_price_column_exists = 0,
    'ALTER TABLE listing_info ADD COLUMN asking_price_yuan DECIMAL(16,2) NULL AFTER total_price',
    'SELECT 1');
PREPARE asking_price_column_statement FROM @asking_price_column_sql;
EXECUTE asking_price_column_statement;
DEALLOCATE PREPARE asking_price_column_statement;

SET @version_column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = 'bk_listing' AND table_name = 'listing_info'
      AND column_name = 'version'
);
SET @version_column_sql = IF(@version_column_exists = 0,
    'ALTER TABLE listing_info ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER deleted',
    'SELECT 1');
PREPARE version_column_statement FROM @version_column_sql;
EXECUTE version_column_statement;
DEALLOCATE PREPARE version_column_statement;

SET @broker_status_index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = 'bk_listing' AND table_name = 'listing_info'
      AND index_name = 'idx_listing_info_broker_status'
);
SET @broker_status_index_sql = IF(@broker_status_index_exists = 0,
    'CREATE INDEX idx_listing_info_broker_status ON listing_info(broker_id, status, deleted, updated_at, id)',
    'SELECT 1');
PREPARE broker_status_index_statement FROM @broker_status_index_sql;
EXECUTE broker_status_index_statement;
DEALLOCATE PREPARE broker_status_index_statement;

SET @status_updated_index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = 'bk_listing' AND table_name = 'listing_info'
      AND index_name = 'idx_listing_info_status_updated'
);
SET @status_updated_index_sql = IF(@status_updated_index_exists = 0,
    'CREATE INDEX idx_listing_info_status_updated ON listing_info(status, deleted, updated_at, id)',
    'SELECT 1');
PREPARE status_updated_index_statement FROM @status_updated_index_sql;
EXECUTE status_updated_index_statement;
DEALLOCATE PREPARE status_updated_index_statement;

SET @owner_customer_index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = 'bk_listing' AND table_name = 'listing_info'
      AND index_name = 'idx_listing_info_owner_customer'
);
SET @owner_customer_index_sql = IF(@owner_customer_index_exists = 0,
    'CREATE INDEX idx_listing_info_owner_customer ON listing_info(owner_customer_id, deleted, id)',
    'SELECT 1');
PREPARE owner_customer_index_statement FROM @owner_customer_index_sql;
EXECUTE owner_customer_index_statement;
DEALLOCATE PREPARE owner_customer_index_statement;

CREATE TABLE IF NOT EXISTS listing_asset (
    id BIGINT NOT NULL AUTO_INCREMENT,
    listing_id BIGINT NOT NULL,
    asset_type VARCHAR(32) NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    visibility VARCHAR(16) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_listing_asset_listing (listing_id, asset_type, deleted, sort_order, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS listing_private_info (
    listing_id BIGINT NOT NULL,
    certificate_no_ciphertext VARBINARY(1024) NULL,
    certificate_key_id VARCHAR(128) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (listing_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS listing_price_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    listing_id BIGINT NOT NULL,
    old_price_yuan DECIMAL(16,2) NULL,
    new_price_yuan DECIMAL(16,2) NOT NULL,
    reason VARCHAR(255) NULL,
    operator_user_id BIGINT NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_listing_price_record_idem (listing_id, operator_user_id, request_id),
    KEY idx_listing_price_record_listing (listing_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS listing_status_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    listing_id BIGINT NOT NULL,
    from_status VARCHAR(32) NULL,
    to_status VARCHAR(32) NOT NULL,
    action VARCHAR(32) NOT NULL,
    reason VARCHAR(255) NULL,
    operator_user_id BIGINT NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_listing_status_record_idem (listing_id, operator_user_id, request_id),
    KEY idx_listing_status_record_listing (listing_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS listing_command_receipt (
    actor_user_id BIGINT NOT NULL,
    operation VARCHAR(64) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    request_hash BINARY(32) NOT NULL,
    listing_id BIGINT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (actor_user_id, operation, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
