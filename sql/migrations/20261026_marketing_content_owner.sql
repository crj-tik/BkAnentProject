-- Add an explicit content owner. Existing content is intentionally left unowned until reviewed.
USE bk_marketing;

SET @owner_column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = 'bk_marketing' AND table_name = 'marketing_content'
      AND column_name = 'owner_broker_id'
);
SET @owner_column_sql = IF(@owner_column_exists = 0,
    'ALTER TABLE marketing_content ADD COLUMN owner_broker_id BIGINT NULL AFTER listing_id',
    'SELECT 1');
PREPARE owner_column_statement FROM @owner_column_sql;
EXECUTE owner_column_statement;
DEALLOCATE PREPARE owner_column_statement;

SET @owner_index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = 'bk_marketing' AND table_name = 'marketing_content'
      AND index_name = 'idx_marketing_content_owner_listing'
);
SET @owner_index_sql = IF(@owner_index_exists = 0,
    'CREATE INDEX idx_marketing_content_owner_listing ON marketing_content(owner_broker_id, listing_id, deleted)',
    'SELECT 1');
PREPARE owner_index_statement FROM @owner_index_sql;
EXECUTE owner_index_statement;
DEALLOCATE PREPARE owner_index_statement;
