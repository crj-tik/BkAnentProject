-- Buyer listing consultation model (implement-internal-listing-business task 2.3).
-- customer_listing_inquiry stores explicit buyer consultations; follow records gain an optional inquiry link.
USE bk_customer;

CREATE TABLE IF NOT EXISTS customer_listing_inquiry (
    id BIGINT NOT NULL AUTO_INCREMENT,
    customer_id BIGINT NOT NULL,
    listing_id BIGINT NOT NULL,
    broker_id BIGINT NOT NULL,
    inquiry_channel VARCHAR(32) NULL,
    inquiry_content TEXT NULL,
    status VARCHAR(32) NOT NULL,
    validation_state VARCHAR(24) NOT NULL,
    checked_listing_version VARCHAR(255) NULL,
    checked_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_customer_listing_inquiry_customer (customer_id, created_at, id),
    KEY idx_customer_listing_inquiry_listing (listing_id, created_at, id),
    KEY idx_customer_listing_inquiry_broker (broker_id, status, updated_at, id),
    KEY idx_customer_listing_inquiry_validation (validation_state, updated_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @inquiry_column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = 'bk_customer' AND table_name = 'customer_follow_record'
      AND column_name = 'inquiry_id'
);
SET @inquiry_column_sql = IF(@inquiry_column_exists = 0,
    'ALTER TABLE customer_follow_record ADD COLUMN inquiry_id BIGINT NULL AFTER result_tag',
    'SELECT 1');
PREPARE inquiry_column_statement FROM @inquiry_column_sql;
EXECUTE inquiry_column_statement;
DEALLOCATE PREPARE inquiry_column_statement;

SET @inquiry_index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = 'bk_customer' AND table_name = 'customer_follow_record'
      AND index_name = 'idx_customer_follow_record_inquiry'
);
SET @inquiry_index_sql = IF(@inquiry_index_exists = 0,
    'CREATE INDEX idx_customer_follow_record_inquiry ON customer_follow_record(inquiry_id, created_at, id)',
    'SELECT 1');
PREPARE inquiry_index_statement FROM @inquiry_index_sql;
EXECUTE inquiry_index_statement;
DEALLOCATE PREPARE inquiry_index_statement;

CREATE TABLE IF NOT EXISTS customer_inquiry_command_receipt (
    actor_user_id BIGINT NOT NULL,
    operation VARCHAR(64) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    request_hash BINARY(32) NOT NULL,
    inquiry_id BIGINT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (actor_user_id, operation, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
