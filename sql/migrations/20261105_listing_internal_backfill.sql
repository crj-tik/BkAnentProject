-- Internal listing business backfill (implement-internal-listing-business task 2.4).
-- Idempotent: exact wan->yuan backfill, migration-baseline price/status records with a system identity.
-- Existing rows without a price keep NULL; owners are NOT auto-associated (pending manual confirmation).
USE bk_listing;

UPDATE listing_info
SET asking_price_yuan = total_price * 10000
WHERE deleted = 0
  AND total_price IS NOT NULL
  AND asking_price_yuan IS NULL;

INSERT IGNORE INTO listing_price_record
    (listing_id, old_price_yuan, new_price_yuan, reason, operator_user_id, request_id)
SELECT id, NULL, total_price * 10000, '迁移基线：旧万元价格精确转元', 0, 'migration-baseline-20261105'
FROM listing_info
WHERE deleted = 0
  AND total_price IS NOT NULL;

INSERT IGNORE INTO listing_status_record
    (listing_id, from_status, to_status, action, reason, operator_user_id, request_id)
SELECT id, NULL, status, 'MIGRATION_BASELINE', '迁移基线：登记存量状态', 0, 'migration-baseline-20261105'
FROM listing_info
WHERE deleted = 0;
