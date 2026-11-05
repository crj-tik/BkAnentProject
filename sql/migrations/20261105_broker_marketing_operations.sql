USE bk_auth;

START TRANSACTION;
SELECT lock_id FROM auth_authorization_writer_lock WHERE lock_id=1 FOR UPDATE;

SET @broker_marketing_grant = NOT EXISTS (
    SELECT 1 FROM auth_authorization_migration
    WHERE migration_id='20261105_broker_marketing_operations'
) AND (
    SELECT COUNT(*)=7 AND COALESCE(SUM(active=1),0)=7
    FROM auth_permission
    WHERE permission_code IN (
        'marketing.content.create.own',
        'marketing.content.read.own',
        'marketing.content.variant.create.own',
        'marketing.assets.bind.own',
        'marketing.campaign.read',
        'marketing.campaign.prepare',
        'marketing.campaign.effects.read'
    )
) AND EXISTS (
    SELECT 1 FROM auth_role
    WHERE BINARY role_code=BINARY 'BROKER' AND active=1 AND deleted=0
);

INSERT IGNORE INTO auth_role_permission (role_id, permission_code)
SELECT r.id, p.permission_code
FROM auth_role r
JOIN auth_permission p ON p.permission_code IN (
    'marketing.content.create.own',
    'marketing.content.read.own',
    'marketing.content.variant.create.own',
    'marketing.assets.bind.own',
    'marketing.campaign.read',
    'marketing.campaign.prepare',
    'marketing.campaign.effects.read'
) AND p.active=1
WHERE @broker_marketing_grant
  AND BINARY r.role_code=BINARY 'BROKER' AND r.active=1 AND r.deleted=0;

UPDATE auth_user_authorization_state s
JOIN auth_user_role ur ON ur.user_id=s.user_id AND ur.active=1
JOIN auth_role r ON r.id=ur.role_id AND BINARY r.role_code=BINARY 'BROKER'
SET s.permission_revision=s.permission_revision+1,
    s.updated_at=CURRENT_TIMESTAMP(6)
WHERE @broker_marketing_grant
  AND (ur.valid_from IS NULL OR ur.valid_from<=CURRENT_TIMESTAMP(6))
  AND (ur.valid_until IS NULL OR ur.valid_until>CURRENT_TIMESTAMP(6));

INSERT INTO auth_authorization_outbox (event_id, affected_users, revisions, reason)
SELECT CONCAT('broker-marketing-20261105-', s.user_id),
       JSON_ARRAY(s.user_id),
       JSON_ARRAY(JSON_OBJECT('userId',s.user_id,
                              'permissionRevision',s.permission_revision,
                              'credentialEpoch',s.credential_epoch,
                              'status',s.status)),
       'ROLE_CHANGE'
FROM auth_user_authorization_state s
JOIN auth_user_role ur ON ur.user_id=s.user_id AND ur.active=1
JOIN auth_role r ON r.id=ur.role_id AND BINARY r.role_code=BINARY 'BROKER'
WHERE @broker_marketing_grant
  AND (ur.valid_from IS NULL OR ur.valid_from<=CURRENT_TIMESTAMP(6))
  AND (ur.valid_until IS NULL OR ur.valid_until>CURRENT_TIMESTAMP(6));

INSERT INTO auth_authorization_migration (migration_id)
SELECT '20261105_broker_marketing_operations' WHERE @broker_marketing_grant;

COMMIT;
