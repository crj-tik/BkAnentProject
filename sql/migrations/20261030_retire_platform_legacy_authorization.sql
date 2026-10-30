-- Retire permissions owned by removed Contract and Settlement domains and replaced Agent entry points.
USE bk_auth;

START TRANSACTION;
SELECT lock_id FROM auth_authorization_writer_lock WHERE lock_id = 1 FOR UPDATE;

SET @platform_legacy_authorization_retirement = NOT EXISTS (
    SELECT 1 FROM auth_authorization_migration
    WHERE migration_id = '20261030_retire_platform_legacy_authorization'
);
SET @platform_legacy_authorization_changed = EXISTS (
    SELECT 1 FROM auth_permission p
    WHERE @platform_legacy_authorization_retirement
      AND (BINARY p.domain IN ('contract', 'settlement')
           OR BINARY p.permission_code LIKE 'contract.%'
           OR BINARY p.permission_code LIKE 'settlement.%'
           OR BINARY p.permission_code IN (
               'agent.workflow.invoke.knowledge',
               'agent.workflow.invoke.business-review',
               'agent.workflow.invoke.platform-operations'))
      AND p.active = 1
) OR EXISTS (
    SELECT 1 FROM auth_role_permission rp
    WHERE @platform_legacy_authorization_retirement
      AND (BINARY rp.permission_code LIKE 'contract.%'
           OR BINARY rp.permission_code LIKE 'settlement.%'
           OR BINARY rp.permission_code IN (
               'agent.workflow.invoke.knowledge',
               'agent.workflow.invoke.business-review',
               'agent.workflow.invoke.platform-operations'))
);

DELETE rp FROM auth_role_permission rp
WHERE @platform_legacy_authorization_retirement
  AND (BINARY rp.permission_code LIKE 'contract.%'
       OR BINARY rp.permission_code LIKE 'settlement.%'
       OR BINARY rp.permission_code IN (
           'agent.workflow.invoke.knowledge',
           'agent.workflow.invoke.business-review',
           'agent.workflow.invoke.platform-operations'));

UPDATE auth_permission p
SET p.active = 0, p.version = p.version + 1
WHERE @platform_legacy_authorization_retirement AND p.active <> 0
  AND (BINARY p.domain IN ('contract', 'settlement')
       OR BINARY p.permission_code LIKE 'contract.%'
       OR BINARY p.permission_code LIKE 'settlement.%'
       OR BINARY p.permission_code IN (
           'agent.workflow.invoke.knowledge',
           'agent.workflow.invoke.business-review',
           'agent.workflow.invoke.platform-operations'));

UPDATE auth_user_authorization_state
SET permission_revision = permission_revision + 1,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE @platform_legacy_authorization_changed;

INSERT INTO auth_authorization_outbox (event_id, affected_users, revisions, reason)
SELECT CONCAT('platform-legacy-permissions-', s.user_id),
       CONCAT('[', s.user_id, ']'),
       JSON_ARRAY(JSON_OBJECT('userId', s.user_id,
                              'permissionRevision', s.permission_revision,
                              'credentialEpoch', s.credential_epoch,
                              'status', s.status)),
       'CATALOG_CHANGE'
FROM auth_user_authorization_state s
WHERE @platform_legacy_authorization_changed;

INSERT INTO auth_authorization_migration (migration_id)
SELECT '20261030_retire_platform_legacy_authorization'
WHERE @platform_legacy_authorization_retirement;
COMMIT;
