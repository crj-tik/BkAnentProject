SELECT IF(
    (SELECT COUNT(*)=3 AND COALESCE(SUM(active=1),0)=3
     FROM bk_auth.auth_permission
     WHERE domain='marketing'
       AND permission_code=CONCAT('marketing.',operation)
       AND permission_code IN ('marketing.channel.beike.publish',
                               'marketing.channel.douyin.publish',
                               'marketing.channel.xiaohongshu.publish'))
    AND (SELECT COUNT(*)=1 FROM bk_auth.auth_authorization_migration
         WHERE migration_id='20261103_marketing_channel_publish_permissions'),
    1, 0);
