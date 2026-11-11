-- Prepare a role without binding it to users. Live administrator behavior is unchanged.
-- The baseline list mirrors AuthzOperationPolicy; a mismatch must fail review before rollout.
USE bk_auth;
SET @prepare_admin_role = NOT EXISTS (
    SELECT 1 FROM auth_authorization_migration WHERE migration_id='20261111_prepare_management_role'
);

START TRANSACTION;
SELECT lock_id FROM auth_authorization_writer_lock WHERE lock_id=1 FOR UPDATE;

-- A pre-existing code is a conflict, not an object we are allowed to take over.
INSERT INTO auth_role(role_code,role_name,description,active,assignable,role_type)
SELECT 'PLATFORM_ADMIN', '平台管理员', '角色授权迁移准备；尚未分配给用户', 1, 0, 'MANAGEMENT'
WHERE @prepare_admin_role;

SELECT id INTO @prepared_admin_role_id FROM auth_role WHERE role_code='PLATFORM_ADMIN';
INSERT INTO auth_role_permission(role_id,permission_code)
SELECT @prepared_admin_role_id, p.permission_code FROM auth_permission p
WHERE @prepare_admin_role AND p.active=1 AND p.permission_code NOT IN (
    'agent.chat.use','agent.workflow.read','agent.workflow.artifact.read',
    'listing.read','listing.create','listing.update.own',
    'customer.read','customer.create','customer.update.own',
    'customer.follow.read.own','customer.follow.write.own',
    'customer.favorite.read.own','customer.favorite.write.own','customer.match.own',
    'compare.report.create','compare.report.read.own','media.task.read.own',
    'notification.message.read.own','notification.message.mark-read.own',
    'interview.initiate','interview.read','interview.respond','interview.end',
    'memory.read','memory.write','memory.search'
);

INSERT INTO auth_authorization_outbox(event_id,affected_users,revisions,reason)
SELECT 'migration-admin-role-preparation-20261111','[]','[]','ROLE_CREATED'
WHERE @prepare_admin_role;
INSERT INTO auth_authorization_migration(migration_id)
SELECT '20261111_prepare_management_role' WHERE @prepare_admin_role;
COMMIT;
