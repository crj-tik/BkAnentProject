-- Retire only reviewed universal baseline operations from permission grants.
-- Their action identifiers remain in signed contexts; actor, owner, relationship and state checks remain mandatory.
USE bk_auth;

START TRANSACTION;
SELECT lock_id FROM auth_authorization_writer_lock WHERE lock_id = 1 FOR UPDATE;
SET @baseline_permission_retirement = NOT EXISTS (
    SELECT 1 FROM auth_authorization_migration
    WHERE migration_id = '20261028_baseline_permission_retirement'
);

DELETE FROM auth_role_permission
WHERE @baseline_permission_retirement
  AND BINARY permission_code IN (
    'agent.chat.use', 'agent.workflow.read', 'agent.workflow.artifact.read',
    'listing.read', 'listing.create', 'listing.update.own',
    'customer.read', 'customer.create', 'customer.update.own',
    'customer.follow.read.own', 'customer.follow.write.own',
    'customer.favorite.read.own', 'customer.favorite.write.own', 'customer.match.own',
    'compare.report.create', 'compare.report.read.own', 'media.task.read.own',
    'notification.message.read.own', 'notification.message.mark-read.own',
    'interview.initiate', 'interview.read', 'interview.respond', 'interview.end',
    'memory.read', 'memory.write', 'memory.search'
  );

UPDATE auth_permission
SET active = 0, version = version + 1
WHERE @baseline_permission_retirement
  AND BINARY permission_code IN (
    'agent.chat.use', 'agent.workflow.read', 'agent.workflow.artifact.read',
    'listing.read', 'listing.create', 'listing.update.own',
    'customer.read', 'customer.create', 'customer.update.own',
    'customer.follow.read.own', 'customer.follow.write.own',
    'customer.favorite.read.own', 'customer.favorite.write.own', 'customer.match.own',
    'compare.report.create', 'compare.report.read.own', 'media.task.read.own',
    'notification.message.read.own', 'notification.message.mark-read.own',
    'interview.initiate', 'interview.read', 'interview.respond', 'interview.end',
    'memory.read', 'memory.write', 'memory.search'
  ) AND active <> 0;

-- Catalog removal changes snapshots for every user; publish a new revision before consumers refresh.
UPDATE auth_user_authorization_state
SET permission_revision = permission_revision + 1,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE @baseline_permission_retirement;

INSERT INTO auth_authorization_outbox (event_id, affected_users, revisions, reason)
SELECT CONCAT('baseline-permissions-', s.user_id),
       CONCAT('[', s.user_id, ']'),
       JSON_ARRAY(JSON_OBJECT('userId', s.user_id,
                              'permissionRevision', s.permission_revision,
                              'credentialEpoch', s.credential_epoch,
                              'status', s.status)),
       'CATALOG_CHANGE'
FROM auth_user_authorization_state s
WHERE @baseline_permission_retirement;

INSERT INTO auth_authorization_migration (migration_id)
SELECT '20261028_baseline_permission_retirement' WHERE @baseline_permission_retirement;
COMMIT;
