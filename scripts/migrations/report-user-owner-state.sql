-- Run after the owner columns and ledgers are installed; this is a read-only inventory report.
SELECT 'bk_customer.customer_profile' AS source_table, COUNT(*) AS total_rows,
       SUM(owner_user_id IS NULL) AS rows_without_owner_user_id
FROM bk_customer.customer_profile WHERE deleted=0
UNION ALL
SELECT 'bk_customer.customer_follow_record', COUNT(*), SUM(owner_user_id IS NULL)
FROM bk_customer.customer_follow_record WHERE deleted=0
UNION ALL
SELECT 'bk_customer.customer_listing_inquiry', COUNT(*), SUM(owner_user_id IS NULL)
FROM bk_customer.customer_listing_inquiry WHERE deleted=0
UNION ALL
SELECT 'bk_listing.listing_info', COUNT(*), SUM(owner_user_id IS NULL)
FROM bk_listing.listing_info WHERE deleted=0
UNION ALL
SELECT 'bk_marketing.marketing_content', COUNT(*), SUM(owner_user_id IS NULL)
FROM bk_marketing.marketing_content WHERE deleted=0;

SELECT 'customer' AS domain, status, COUNT(*) AS ledger_rows
FROM bk_customer.customer_owner_migration_ledger GROUP BY status
UNION ALL
SELECT 'listing', status, COUNT(*)
FROM bk_listing.listing_owner_migration_ledger GROUP BY status
UNION ALL
SELECT 'marketing', status, COUNT(*)
FROM bk_marketing.marketing_owner_migration_ledger GROUP BY status;

SELECT 'customer' AS domain, record_type, legacy_broker_id,
       COUNT(DISTINCT candidate_user_id) AS candidate_users
FROM bk_customer.customer_owner_migration_ledger
WHERE status<>'REJECTED' AND candidate_user_id IS NOT NULL
GROUP BY record_type, legacy_broker_id HAVING COUNT(DISTINCT candidate_user_id)>1
UNION ALL
SELECT 'listing', record_type, legacy_broker_id, COUNT(DISTINCT candidate_user_id)
FROM bk_listing.listing_owner_migration_ledger
WHERE status<>'REJECTED' AND candidate_user_id IS NOT NULL
GROUP BY record_type, legacy_broker_id HAVING COUNT(DISTINCT candidate_user_id)>1
UNION ALL
SELECT 'marketing', record_type, legacy_broker_id, COUNT(DISTINCT candidate_user_id)
FROM bk_marketing.marketing_owner_migration_ledger
WHERE status<>'REJECTED' AND candidate_user_id IS NOT NULL
GROUP BY record_type, legacy_broker_id HAVING COUNT(DISTINCT candidate_user_id)>1;
