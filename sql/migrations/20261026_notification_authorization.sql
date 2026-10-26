-- Register exact Notification operations. No role grants are inferred.
USE bk_auth;

SET @notification_authorization_migration = NOT EXISTS (
    SELECT 1 FROM auth_authorization_migration
    WHERE migration_id = '20261026_notification_authorization'
);

START TRANSACTION;
SELECT lock_id FROM auth_authorization_writer_lock WHERE lock_id = 1 FOR UPDATE;

INSERT INTO auth_permission (permission_code, domain, operation, active, version)
SELECT permissions.permission_code, permissions.domain, permissions.operation, 1, 1 FROM (
    SELECT 'notification.message.read.own' permission_code, 'notification' domain, 'message.read.own' operation
    UNION ALL SELECT 'notification.message.mark-read.own', 'notification', 'message.mark-read.own'
    UNION ALL SELECT 'notification.message.send.own', 'notification', 'message.send.own'
    UNION ALL SELECT 'notification.message.send.all', 'notification', 'message.send.all'
    UNION ALL SELECT 'notification.email.send.all', 'notification', 'email.send.all'
    UNION ALL SELECT 'notification.robot.send.all', 'notification', 'robot.send.all'
    UNION ALL SELECT 'notification.workflow.events.read.all', 'notification', 'workflow.events.read.all'
) permissions
WHERE @notification_authorization_migration
ON DUPLICATE KEY UPDATE active = 1, version = version + 1;

UPDATE auth_user_authorization_state
SET permission_revision = permission_revision + 1,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE @notification_authorization_migration;

INSERT INTO auth_authorization_outbox (event_id, affected_users, revisions, reason)
SELECT CONCAT('notification-auth-', s.user_id),
       CONCAT('[', s.user_id, ']'),
       JSON_ARRAY(JSON_OBJECT('userId', s.user_id,
                              'permissionRevision', s.permission_revision,
                              'credentialEpoch', s.credential_epoch,
                              'status', s.status)),
       'CATALOG_CHANGE'
FROM auth_user_authorization_state s
WHERE @notification_authorization_migration;

INSERT INTO auth_authorization_migration (migration_id)
SELECT '20261026_notification_authorization'
WHERE @notification_authorization_migration;
COMMIT;
