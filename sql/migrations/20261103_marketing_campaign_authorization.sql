USE bk_auth;
SET @campaign_auth_migration = NOT EXISTS (SELECT 1 FROM auth_authorization_migration WHERE migration_id='20261103_marketing_campaign_authorization')
AND NOT EXISTS (
 SELECT 1 FROM auth_permission p JOIN (
  SELECT 'marketing.campaign.read' code, 'campaign.read' operation
  UNION ALL SELECT 'marketing.campaign.prepare', 'campaign.prepare'
  UNION ALL SELECT 'marketing.campaign.publish', 'campaign.publish'
  UNION ALL SELECT 'marketing.campaign.effects.read', 'campaign.effects.read'
  UNION ALL SELECT 'marketing.campaign.assets.manage', 'campaign.assets.manage'
  UNION ALL SELECT 'marketing.content.review.own', 'content.review.own'
  UNION ALL SELECT 'marketing.content.review.all', 'content.review.all'
 ) expected ON expected.code=p.permission_code
 WHERE p.active<>1 OR p.domain<>'marketing' OR p.operation<>expected.operation
);
START TRANSACTION;
SELECT lock_id FROM auth_authorization_writer_lock WHERE lock_id=1 FOR UPDATE;
INSERT IGNORE INTO auth_permission (permission_code, domain, operation, active, version)
SELECT p.code, 'marketing', p.operation, 1, 1 FROM (
 SELECT 'marketing.campaign.read' code, 'campaign.read' operation
 UNION ALL SELECT 'marketing.campaign.prepare', 'campaign.prepare'
 UNION ALL SELECT 'marketing.campaign.publish', 'campaign.publish'
 UNION ALL SELECT 'marketing.campaign.effects.read', 'campaign.effects.read'
 UNION ALL SELECT 'marketing.campaign.assets.manage', 'campaign.assets.manage'
 UNION ALL SELECT 'marketing.content.review.own', 'content.review.own'
 UNION ALL SELECT 'marketing.content.review.all', 'content.review.all'
) p WHERE @campaign_auth_migration;
UPDATE auth_user_authorization_state SET permission_revision=permission_revision+1, updated_at=CURRENT_TIMESTAMP(6) WHERE @campaign_auth_migration;
INSERT INTO auth_authorization_outbox (event_id, affected_users, revisions, reason)
SELECT CONCAT('campaign-auth-20261103-', s.user_id), CONCAT('[',s.user_id,']'),
 JSON_ARRAY(JSON_OBJECT('userId',s.user_id,'permissionRevision',s.permission_revision,'credentialEpoch',s.credential_epoch,'status',s.status)), 'CATALOG_CHANGE'
FROM auth_user_authorization_state s WHERE @campaign_auth_migration;
INSERT INTO auth_authorization_migration (migration_id) SELECT '20261103_marketing_campaign_authorization' WHERE @campaign_auth_migration;
COMMIT;
