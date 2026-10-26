SELECT CASE WHEN COUNT(*) = 13 THEN 1 ELSE 0 END
FROM information_schema.tables
WHERE LOWER(table_schema) = LOWER(SCHEMA())
  AND LOWER(table_name) IN (
    'marketing_campaign_content_revision',
    'marketing_campaign_brand_asset',
    'marketing_campaign_account',
    'marketing_campaign_plan',
    'marketing_campaign_plan_target',
    'marketing_campaign_publish_request',
    'marketing_campaign_publish_receipt',
    'marketing_campaign_publish_attempt',
    'marketing_campaign_outbox',
    'marketing_campaign_event',
    'marketing_campaign_effect_observation',
    'marketing_campaign_effect_revision',
    'marketing_campaign_legacy_map'
  );
