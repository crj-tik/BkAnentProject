-- KI-272: diagnostic only; never clear authoritative UCID ownership on rollback.
-- A legacy BROKER mapping can change after backfill, and clearing owner_user_id
-- would let the current mapped user gain access to a different person's record.
-- Roll back only to a verified compatible service version that preserves UCID
-- precedence. Investigate every non-ALIGNED row without inferring ownership.

SELECT 'DIAGNOSTIC ONLY: no ownership or ledger rows were changed' AS notice;

SELECT r.domain_name, r.record_type, r.record_id, r.ledger_user_id,
       r.current_owner_user_id, r.legacy_broker_id, r.current_broker_id,
       i.user_id AS mapped_user_id,
       CASE
         WHEN r.ledger_user_id IS NULL OR r.ledger_user_id <= 0
              OR r.legacy_broker_id IS NULL OR r.legacy_broker_id <= 0
           THEN 'INVALID_LEDGER'
         WHEN r.record_exists IS NULL THEN 'RECORD_MISSING'
         WHEN NOT (r.current_owner_user_id <=> r.ledger_user_id) THEN 'OWNER_CHANGED'
         WHEN NOT (r.current_broker_id <=> r.legacy_broker_id) THEN 'BROKER_CHANGED'
         WHEN i.user_id IS NULL THEN 'MAPPING_MISSING'
         WHEN i.user_id <> r.ledger_user_id THEN 'MAPPING_CHANGED'
         ELSE 'ALIGNED_RETAIN_OWNER'
       END AS assessment
FROM (
    SELECT 'customer' AS domain_name, l.record_type, l.record_id,
           l.candidate_user_id AS ledger_user_id, l.legacy_broker_id,
           p.id AS record_exists, p.owner_user_id AS current_owner_user_id,
           p.broker_id AS current_broker_id
    FROM bk_customer.customer_owner_migration_ledger l
    LEFT JOIN bk_customer.customer_profile p ON l.record_type='PROFILE' AND p.id=l.record_id
    WHERE l.record_type='PROFILE' AND l.status='APPLIED'
    UNION ALL
    SELECT 'customer', l.record_type, l.record_id, l.candidate_user_id, l.legacy_broker_id,
           f.id, f.owner_user_id, f.broker_id
    FROM bk_customer.customer_owner_migration_ledger l
    LEFT JOIN bk_customer.customer_follow_record f ON l.record_type='FOLLOW' AND f.id=l.record_id
    WHERE l.record_type='FOLLOW' AND l.status='APPLIED'
    UNION ALL
    SELECT 'customer', l.record_type, l.record_id, l.candidate_user_id, l.legacy_broker_id,
           q.id, q.owner_user_id, q.broker_id
    FROM bk_customer.customer_owner_migration_ledger l
    LEFT JOIN bk_customer.customer_listing_inquiry q ON l.record_type='INQUIRY' AND q.id=l.record_id
    WHERE l.record_type='INQUIRY' AND l.status='APPLIED'
    UNION ALL
    SELECT 'listing', l.record_type, l.record_id, l.candidate_user_id, l.legacy_broker_id,
           p.id, p.owner_user_id, p.broker_id
    FROM bk_listing.listing_owner_migration_ledger l
    LEFT JOIN bk_listing.listing_info p ON l.record_type='LISTING' AND p.id=l.record_id
    WHERE l.record_type='LISTING' AND l.status='APPLIED'
    UNION ALL
    SELECT 'marketing', l.record_type, l.record_id, l.candidate_user_id, l.legacy_broker_id,
           c.id, c.owner_user_id, c.owner_broker_id
    FROM bk_marketing.marketing_owner_migration_ledger l
    LEFT JOIN bk_marketing.marketing_content c ON l.record_type='CONTENT' AND c.id=l.record_id
    WHERE l.record_type='CONTENT' AND l.status='APPLIED'
) r
LEFT JOIN bk_auth.auth_user_business_identity i
  ON i.identity_type='BROKER' AND i.identity_id=r.legacy_broker_id
ORDER BY r.domain_name, r.record_type, r.record_id;
