-- Evidence-preserving rollback for the manual owner backfill.
-- Run only after writes to the affected records are paused and a database backup is verified.
-- Rows are cleared only when both the current owner and legacy business id still match the
-- APPLIED ledger row. Any diverged row is left untouched and reported by the final queries.

START TRANSACTION;

UPDATE bk_customer.customer_profile AS r
JOIN bk_customer.customer_owner_migration_ledger AS l
  ON l.record_type='PROFILE' AND l.record_id=r.id AND l.status='APPLIED'
SET r.owner_user_id=NULL, l.status='VERIFIED', l.applied_at=NULL
WHERE r.owner_user_id=l.candidate_user_id
  AND r.broker_id=l.legacy_broker_id;

UPDATE bk_customer.customer_follow_record AS r
JOIN bk_customer.customer_owner_migration_ledger AS l
  ON l.record_type='FOLLOW' AND l.record_id=r.id AND l.status='APPLIED'
SET r.owner_user_id=NULL, l.status='VERIFIED', l.applied_at=NULL
WHERE r.owner_user_id=l.candidate_user_id
  AND r.broker_id=l.legacy_broker_id;

UPDATE bk_customer.customer_listing_inquiry AS r
JOIN bk_customer.customer_owner_migration_ledger AS l
  ON l.record_type='INQUIRY' AND l.record_id=r.id AND l.status='APPLIED'
SET r.owner_user_id=NULL, l.status='VERIFIED', l.applied_at=NULL
WHERE r.owner_user_id=l.candidate_user_id
  AND r.broker_id=l.legacy_broker_id;

UPDATE bk_listing.listing_info AS r
JOIN bk_listing.listing_owner_migration_ledger AS l
  ON l.record_type='LISTING' AND l.record_id=r.id AND l.status='APPLIED'
SET r.owner_user_id=NULL, l.status='VERIFIED', l.applied_at=NULL
WHERE r.owner_user_id=l.candidate_user_id
  AND r.broker_id=l.legacy_broker_id;

UPDATE bk_marketing.marketing_content AS r
JOIN bk_marketing.marketing_owner_migration_ledger AS l
  ON l.record_type='CONTENT' AND l.record_id=r.id AND l.status='APPLIED'
SET r.owner_user_id=NULL, l.status='VERIFIED', l.applied_at=NULL
WHERE r.owner_user_id=l.candidate_user_id
  AND r.owner_broker_id=l.legacy_broker_id;

COMMIT;

-- Any returned row needs manual review; the target changed after backfill or disappeared.
SELECT 'customer_profile' AS source_table, l.record_id, l.candidate_user_id,
       r.owner_user_id, l.legacy_broker_id, r.broker_id
FROM bk_customer.customer_owner_migration_ledger AS l
LEFT JOIN bk_customer.customer_profile AS r ON r.id=l.record_id
WHERE l.record_type='PROFILE' AND l.status='APPLIED'
  AND (r.id IS NULL OR NOT (r.owner_user_id <=> l.candidate_user_id)
       OR NOT (r.broker_id <=> l.legacy_broker_id))
UNION ALL
SELECT 'customer_follow_record', l.record_id, l.candidate_user_id,
       r.owner_user_id, l.legacy_broker_id, r.broker_id
FROM bk_customer.customer_owner_migration_ledger AS l
LEFT JOIN bk_customer.customer_follow_record AS r ON r.id=l.record_id
WHERE l.record_type='FOLLOW' AND l.status='APPLIED'
  AND (r.id IS NULL OR NOT (r.owner_user_id <=> l.candidate_user_id)
       OR NOT (r.broker_id <=> l.legacy_broker_id))
UNION ALL
SELECT 'customer_listing_inquiry', l.record_id, l.candidate_user_id,
       r.owner_user_id, l.legacy_broker_id, r.broker_id
FROM bk_customer.customer_owner_migration_ledger AS l
LEFT JOIN bk_customer.customer_listing_inquiry AS r ON r.id=l.record_id
WHERE l.record_type='INQUIRY' AND l.status='APPLIED'
  AND (r.id IS NULL OR NOT (r.owner_user_id <=> l.candidate_user_id)
       OR NOT (r.broker_id <=> l.legacy_broker_id))
UNION ALL
SELECT 'listing_info', l.record_id, l.candidate_user_id,
       r.owner_user_id, l.legacy_broker_id, r.broker_id
FROM bk_listing.listing_owner_migration_ledger AS l
LEFT JOIN bk_listing.listing_info AS r ON r.id=l.record_id
WHERE l.record_type='LISTING' AND l.status='APPLIED'
  AND (r.id IS NULL OR NOT (r.owner_user_id <=> l.candidate_user_id)
       OR NOT (r.broker_id <=> l.legacy_broker_id))
UNION ALL
SELECT 'marketing_content', l.record_id, l.candidate_user_id,
       r.owner_user_id, l.legacy_broker_id, r.owner_broker_id
FROM bk_marketing.marketing_owner_migration_ledger AS l
LEFT JOIN bk_marketing.marketing_content AS r ON r.id=l.record_id
WHERE l.record_type='CONTENT' AND l.status='APPLIED'
  AND (r.id IS NULL OR NOT (r.owner_user_id <=> l.candidate_user_id)
       OR NOT (r.owner_broker_id <=> l.legacy_broker_id));
