SELECT IF(
    (SELECT COUNT(*)=7 AND COALESCE(SUM(active=1),0)=7
     FROM bk_auth.auth_permission
     WHERE permission_code IN (
        'marketing.content.create.own',
        'marketing.content.read.own',
        'marketing.content.variant.create.own',
        'marketing.assets.bind.own',
        'marketing.campaign.read',
        'marketing.campaign.prepare',
        'marketing.campaign.effects.read'
     ))
    AND (SELECT COUNT(*)=7
         FROM bk_auth.auth_role_permission rp
         JOIN bk_auth.auth_role r ON r.id=rp.role_id
         WHERE BINARY r.role_code=BINARY 'BROKER'
           AND rp.permission_code IN (
              'marketing.content.create.own',
              'marketing.content.read.own',
              'marketing.content.variant.create.own',
              'marketing.assets.bind.own',
              'marketing.campaign.read',
              'marketing.campaign.prepare',
              'marketing.campaign.effects.read'
           ))
    AND (SELECT COUNT(*)=1 FROM bk_auth.auth_authorization_migration
         WHERE migration_id='20261105_broker_marketing_operations'),
    1, 0);
