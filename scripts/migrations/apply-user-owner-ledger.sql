-- Manual, evidence-gated backfill. Run only after reviewers insert VERIFIED ledger rows.
-- The nullable-owner and old-broker predicates are compare-and-set guards against concurrent edits.
USE bk_customer;
DROP TEMPORARY TABLE IF EXISTS customer_owner_unambiguous_broker;
CREATE TEMPORARY TABLE customer_owner_unambiguous_broker AS
SELECT legacy_broker_id
FROM customer_owner_migration_ledger
WHERE status<>'REJECTED' AND candidate_user_id IS NOT NULL
GROUP BY legacy_broker_id
HAVING COUNT(DISTINCT candidate_user_id)=1;
START TRANSACTION;

UPDATE customer_profile AS r
JOIN customer_owner_migration_ledger AS l
  ON l.record_type='PROFILE' AND l.record_id=r.id
JOIN customer_owner_unambiguous_broker AS m ON m.legacy_broker_id=l.legacy_broker_id
SET r.owner_user_id=l.candidate_user_id,
    l.status='APPLIED', l.applied_at=CURRENT_TIMESTAMP, l.updated_at=CURRENT_TIMESTAMP
WHERE l.status='VERIFIED' AND l.candidate_user_id IS NOT NULL AND l.candidate_user_id>0
  AND l.verified_by_user_id IS NOT NULL AND l.evidence_reference IS NOT NULL
  AND EXISTS (SELECT 1 FROM bk_auth.user_account u WHERE u.id=l.candidate_user_id AND u.deleted=0)
  AND EXISTS (SELECT 1 FROM bk_auth.user_account u WHERE u.id=l.verified_by_user_id AND u.deleted=0)
  AND l.legacy_broker_id IS NOT NULL AND r.owner_user_id IS NULL
  AND r.broker_id=l.legacy_broker_id;

UPDATE customer_follow_record AS r
JOIN customer_owner_migration_ledger AS l
  ON l.record_type='FOLLOW' AND l.record_id=r.id
JOIN customer_owner_unambiguous_broker AS m ON m.legacy_broker_id=l.legacy_broker_id
SET r.owner_user_id=l.candidate_user_id,
    l.status='APPLIED', l.applied_at=CURRENT_TIMESTAMP, l.updated_at=CURRENT_TIMESTAMP
WHERE l.status='VERIFIED' AND l.candidate_user_id IS NOT NULL AND l.candidate_user_id>0
  AND l.verified_by_user_id IS NOT NULL AND l.evidence_reference IS NOT NULL
  AND EXISTS (SELECT 1 FROM bk_auth.user_account u WHERE u.id=l.candidate_user_id AND u.deleted=0)
  AND EXISTS (SELECT 1 FROM bk_auth.user_account u WHERE u.id=l.verified_by_user_id AND u.deleted=0)
  AND l.legacy_broker_id IS NOT NULL AND r.owner_user_id IS NULL
  AND r.broker_id=l.legacy_broker_id;

UPDATE customer_listing_inquiry AS r
JOIN customer_owner_migration_ledger AS l
  ON l.record_type='INQUIRY' AND l.record_id=r.id
JOIN customer_owner_unambiguous_broker AS m ON m.legacy_broker_id=l.legacy_broker_id
SET r.owner_user_id=l.candidate_user_id,
    l.status='APPLIED', l.applied_at=CURRENT_TIMESTAMP, l.updated_at=CURRENT_TIMESTAMP
WHERE l.status='VERIFIED' AND l.candidate_user_id IS NOT NULL AND l.candidate_user_id>0
  AND l.verified_by_user_id IS NOT NULL AND l.evidence_reference IS NOT NULL
  AND EXISTS (SELECT 1 FROM bk_auth.user_account u WHERE u.id=l.candidate_user_id AND u.deleted=0)
  AND EXISTS (SELECT 1 FROM bk_auth.user_account u WHERE u.id=l.verified_by_user_id AND u.deleted=0)
  AND l.legacy_broker_id IS NOT NULL AND r.owner_user_id IS NULL
  AND r.broker_id=l.legacy_broker_id;
COMMIT;
DROP TEMPORARY TABLE customer_owner_unambiguous_broker;

USE bk_listing;
DROP TEMPORARY TABLE IF EXISTS listing_owner_unambiguous_broker;
CREATE TEMPORARY TABLE listing_owner_unambiguous_broker AS
SELECT legacy_broker_id
FROM listing_owner_migration_ledger
WHERE status<>'REJECTED' AND candidate_user_id IS NOT NULL
GROUP BY legacy_broker_id
HAVING COUNT(DISTINCT candidate_user_id)=1;
START TRANSACTION;
UPDATE listing_info AS r
JOIN listing_owner_migration_ledger AS l
  ON l.record_type='LISTING' AND l.record_id=r.id
JOIN listing_owner_unambiguous_broker AS m ON m.legacy_broker_id=l.legacy_broker_id
SET r.owner_user_id=l.candidate_user_id,
    l.status='APPLIED', l.applied_at=CURRENT_TIMESTAMP, l.updated_at=CURRENT_TIMESTAMP
WHERE l.status='VERIFIED' AND l.candidate_user_id IS NOT NULL AND l.candidate_user_id>0
  AND l.verified_by_user_id IS NOT NULL AND l.evidence_reference IS NOT NULL
  AND EXISTS (SELECT 1 FROM bk_auth.user_account u WHERE u.id=l.candidate_user_id AND u.deleted=0)
  AND EXISTS (SELECT 1 FROM bk_auth.user_account u WHERE u.id=l.verified_by_user_id AND u.deleted=0)
  AND l.legacy_broker_id IS NOT NULL AND r.owner_user_id IS NULL
  AND r.broker_id=l.legacy_broker_id;
COMMIT;
DROP TEMPORARY TABLE listing_owner_unambiguous_broker;

USE bk_marketing;
DROP TEMPORARY TABLE IF EXISTS marketing_owner_unambiguous_broker;
CREATE TEMPORARY TABLE marketing_owner_unambiguous_broker AS
SELECT legacy_broker_id
FROM marketing_owner_migration_ledger
WHERE status<>'REJECTED' AND candidate_user_id IS NOT NULL
GROUP BY legacy_broker_id
HAVING COUNT(DISTINCT candidate_user_id)=1;
START TRANSACTION;
UPDATE marketing_content AS r
JOIN marketing_owner_migration_ledger AS l
  ON l.record_type='CONTENT' AND l.record_id=r.id
JOIN marketing_owner_unambiguous_broker AS m ON m.legacy_broker_id=l.legacy_broker_id
SET r.owner_user_id=l.candidate_user_id,
    l.status='APPLIED', l.applied_at=CURRENT_TIMESTAMP, l.updated_at=CURRENT_TIMESTAMP
WHERE l.status='VERIFIED' AND l.candidate_user_id IS NOT NULL AND l.candidate_user_id>0
  AND l.verified_by_user_id IS NOT NULL AND l.evidence_reference IS NOT NULL
  AND EXISTS (SELECT 1 FROM bk_auth.user_account u WHERE u.id=l.candidate_user_id AND u.deleted=0)
  AND EXISTS (SELECT 1 FROM bk_auth.user_account u WHERE u.id=l.verified_by_user_id AND u.deleted=0)
  AND l.legacy_broker_id IS NOT NULL AND r.owner_user_id IS NULL
  AND r.owner_broker_id=l.legacy_broker_id;
COMMIT;
DROP TEMPORARY TABLE marketing_owner_unambiguous_broker;
