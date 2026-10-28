-- Read-only pre-migration inventory. Do not run against a shared database without the migration owner's approval.
SELECT 'marketing_content' AS source_table, COUNT(*) AS row_count, MIN(id) AS min_id, MAX(id) AS max_id
FROM bk_marketing.marketing_content;

SELECT 'promotion_publish_record' AS source_table, publish_status, COUNT(*) AS row_count,
       MIN(id) AS min_id, MAX(id) AS max_id
FROM bk_promotion.promotion_publish_record
GROUP BY publish_status
ORDER BY publish_status;

SELECT 'promotion_effect_stat' AS source_table, COUNT(*) AS row_count,
       MIN(id) AS min_id, MAX(id) AS max_id
FROM bk_promotion.promotion_effect_stat;

SELECT 'brand_asset' AS source_table, COUNT(*) AS row_count, MIN(id) AS min_id, MAX(id) AS max_id
FROM bk_promotion.brand_asset;

SELECT COUNT(*) AS publish_records_without_content
FROM bk_promotion.promotion_publish_record p
LEFT JOIN bk_marketing.marketing_content c ON c.id = p.content_id
WHERE c.id IS NULL;

SELECT p.id, p.content_id, p.platform, p.publish_status, p.external_publish_id, p.updated_at
FROM bk_promotion.promotion_publish_record p
WHERE UPPER(p.publish_status) IN ('PENDING', 'REVIEWING', 'PROCESSING', 'UNKNOWN', 'OUTCOME_UNKNOWN')
   OR p.publish_status IS NULL
   OR UPPER(p.publish_status) NOT IN ('SUCCESS', 'FAILED', 'TAKEN_DOWN')
ORDER BY p.updated_at, p.id;

SELECT publish_record_id, stat_date, COUNT(*) AS duplicate_count
FROM bk_promotion.promotion_effect_stat
GROUP BY publish_record_id, stat_date
HAVING COUNT(*) > 1
ORDER BY publish_record_id, stat_date;

SELECT id, asset_type, asset_name, asset_url, platform_scope, status
FROM bk_promotion.brand_asset
ORDER BY id;
