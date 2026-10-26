-- Register the explicit business-wide interview read operation and invalidate old snapshots.
USE bk_auth;

START TRANSACTION;
SELECT lock_id FROM auth_authorization_writer_lock WHERE lock_id = 1 FOR UPDATE;
SET @interview_read_all_migration = NOT EXISTS (
    SELECT 1 FROM auth_authorization_migration
    WHERE migration_id = '20261026_interview_read_all_permission'
);

INSERT INTO auth_permission (permission_code, domain, operation, active, version)
SELECT 'interview.read.all', 'interview', 'read.all', 1, 2
WHERE @interview_read_all_migration
ON DUPLICATE KEY UPDATE version = GREATEST(version, VALUES(version));

UPDATE auth_user_authorization_state
SET permission_revision = permission_revision + 1,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE @interview_read_all_migration;

INSERT INTO auth_authorization_outbox (event_id, affected_users, revisions, reason)
SELECT CONCAT('interview-read-all-', s.user_id),
       CONCAT('[', s.user_id, ']'),
       JSON_ARRAY(JSON_OBJECT('userId', s.user_id,
                              'permissionRevision', s.permission_revision,
                              'credentialEpoch', s.credential_epoch,
                              'status', s.status)),
       'CATALOG_CHANGE'
FROM auth_user_authorization_state s
JOIN user_account u ON u.id = s.user_id
WHERE @interview_read_all_migration;

INSERT INTO auth_authorization_migration (migration_id)
SELECT '20261026_interview_read_all_permission' WHERE @interview_read_all_migration;
COMMIT;
