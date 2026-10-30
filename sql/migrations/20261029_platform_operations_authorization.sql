-- Register the unified Agent and its three exact capability operations without role grants.
USE bk_auth;
START TRANSACTION;
SELECT lock_id FROM auth_authorization_writer_lock WHERE lock_id=1 FOR UPDATE;
SET @platform_operations_catalog = NOT EXISTS (
    SELECT 1 FROM auth_authorization_migration WHERE migration_id='20261029_platform_operations_authorization'
);
INSERT IGNORE INTO auth_permission (permission_code,domain,operation,active,version)
SELECT p.code,'agent',p.operation,1,1 FROM (
    SELECT 'agent.workflow.invoke.platform-operations' code, 'workflow.invoke.platform-operations' operation
    UNION ALL SELECT 'agent.workflow.invoke.platform-operations.analytics','workflow.invoke.platform-operations.analytics'
    UNION ALL SELECT 'agent.workflow.invoke.platform-operations.review','workflow.invoke.platform-operations.review'
    UNION ALL SELECT 'agent.workflow.invoke.platform-operations.knowledge','workflow.invoke.platform-operations.knowledge'
) p WHERE @platform_operations_catalog;
UPDATE auth_user_authorization_state SET permission_revision=permission_revision+1, updated_at=CURRENT_TIMESTAMP(6)
WHERE @platform_operations_catalog;
INSERT INTO auth_authorization_outbox (event_id,affected_users,revisions,reason)
SELECT CONCAT('platform-operations-catalog-',s.user_id), CONCAT('[',s.user_id,']'),
       JSON_ARRAY(JSON_OBJECT('userId',s.user_id,'permissionRevision',s.permission_revision,
                              'credentialEpoch',s.credential_epoch,'status',s.status)), 'CATALOG_CHANGE'
FROM auth_user_authorization_state s WHERE @platform_operations_catalog;
INSERT INTO auth_authorization_migration (migration_id)
SELECT '20261029_platform_operations_authorization' WHERE @platform_operations_catalog;
COMMIT;
