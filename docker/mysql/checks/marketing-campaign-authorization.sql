SELECT IF((SELECT COUNT(*)=7 AND COALESCE(SUM(active=1),0)=7 FROM bk_auth.auth_permission
 WHERE domain='marketing' AND permission_code=CONCAT('marketing.',operation)
 AND permission_code IN ('marketing.campaign.read','marketing.campaign.prepare','marketing.campaign.publish',
 'marketing.campaign.effects.read','marketing.campaign.assets.manage','marketing.content.review.own','marketing.content.review.all'))
 AND (SELECT COUNT(*)=1 FROM bk_auth.auth_authorization_migration WHERE migration_id='20261103_marketing_campaign_authorization'),1,0);
