USE bk_auth;
SET @channel_publish_migration = NOT EXISTS (
    SELECT 1 FROM auth_authorization_migration
    WHERE migration_id='20261103_marketing_channel_publish_permissions'
) AND NOT EXISTS (
    SELECT 1 FROM auth_permission p JOIN (
        SELECT 'marketing.channel.beike.publish' code, 'channel.beike.publish' operation
        UNION ALL SELECT 'marketing.channel.douyin.publish', 'channel.douyin.publish'
        UNION ALL SELECT 'marketing.channel.xiaohongshu.publish', 'channel.xiaohongshu.publish'
    ) expected ON expected.code=p.permission_code
    WHERE p.active<>1 OR p.domain<>'marketing' OR p.operation<>expected.operation
);
START TRANSACTION;
SELECT lock_id FROM auth_authorization_writer_lock WHERE lock_id=1 FOR UPDATE;
INSERT IGNORE INTO auth_permission (permission_code, domain, operation, active, version)
SELECT p.code, 'marketing', p.operation, 1, 1 FROM (
    SELECT 'marketing.channel.beike.publish' code, 'channel.beike.publish' operation
    UNION ALL SELECT 'marketing.channel.douyin.publish', 'channel.douyin.publish'
    UNION ALL SELECT 'marketing.channel.xiaohongshu.publish', 'channel.xiaohongshu.publish'
) p WHERE @channel_publish_migration;
UPDATE auth_user_authorization_state
SET permission_revision=permission_revision+1, updated_at=CURRENT_TIMESTAMP(6)
WHERE @channel_publish_migration;
INSERT INTO auth_authorization_outbox (event_id, affected_users, revisions, reason)
SELECT CONCAT('channel-auth-20261103-', s.user_id), CONCAT('[',s.user_id,']'),
       JSON_ARRAY(JSON_OBJECT('userId',s.user_id,'permissionRevision',s.permission_revision,
                              'credentialEpoch',s.credential_epoch,'status',s.status)), 'CATALOG_CHANGE'
FROM auth_user_authorization_state s WHERE @channel_publish_migration;
INSERT INTO auth_authorization_migration (migration_id)
SELECT '20261103_marketing_channel_publish_permissions' WHERE @channel_publish_migration;
COMMIT;
