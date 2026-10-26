-- Register exact Compare and Media operations. No role grants are inferred.
USE bk_auth;

SET @compare_media_authorization_migration = NOT EXISTS (
    SELECT 1 FROM auth_authorization_migration
    WHERE migration_id = '20261026_compare_media_authorization'
);

START TRANSACTION;
SELECT lock_id FROM auth_authorization_writer_lock WHERE lock_id = 1 FOR UPDATE;

INSERT INTO auth_permission (permission_code, domain, operation, active, version)
SELECT permissions.permission_code, permissions.domain, permissions.operation, 1, 1 FROM (
    SELECT 'compare.report.create' permission_code, 'compare' domain, 'report.create' operation
    UNION ALL SELECT 'compare.report.read.own', 'compare', 'report.read.own'
    UNION ALL SELECT 'compare.report.share.public', 'compare', 'report.share.public'
    UNION ALL SELECT 'media.task.submit.own', 'media', 'task.submit.own'
    UNION ALL SELECT 'media.task.submit.all', 'media', 'task.submit.all'
    UNION ALL SELECT 'media.task.read.own', 'media', 'task.read.own'
    UNION ALL SELECT 'media.task.read.all', 'media', 'task.read.all'
    UNION ALL SELECT 'media.generate.background', 'media', 'generate.background'
) permissions
WHERE @compare_media_authorization_migration
ON DUPLICATE KEY UPDATE active = 1, version = version + 1;

UPDATE auth_user_authorization_state
SET permission_revision = permission_revision + 1,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE @compare_media_authorization_migration;

INSERT INTO auth_authorization_outbox (event_id, affected_users, revisions, reason)
SELECT CONCAT('compare-media-auth-', s.user_id),
       CONCAT('[', s.user_id, ']'),
       JSON_ARRAY(JSON_OBJECT('userId', s.user_id,
                              'permissionRevision', s.permission_revision,
                              'credentialEpoch', s.credential_epoch,
                              'status', s.status)),
       'CATALOG_CHANGE'
FROM auth_user_authorization_state s
WHERE @compare_media_authorization_migration;

INSERT INTO auth_authorization_migration (migration_id)
SELECT '20261026_compare_media_authorization'
WHERE @compare_media_authorization_migration;
COMMIT;
