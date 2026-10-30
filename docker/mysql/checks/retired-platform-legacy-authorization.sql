SELECT IF(
    (SELECT COUNT(*) = 0 FROM bk_auth.auth_permission p
     WHERE p.active = 1
       AND (BINARY p.domain IN ('contract', 'settlement')
            OR BINARY p.permission_code LIKE 'contract.%'
            OR BINARY p.permission_code LIKE 'settlement.%'
            OR BINARY p.permission_code IN (
                'agent.workflow.invoke.knowledge',
                'agent.workflow.invoke.business-review',
                'agent.workflow.invoke.platform-operations')))
    AND (SELECT COUNT(*) = 0 FROM bk_auth.auth_role_permission rp
         WHERE BINARY rp.permission_code LIKE 'contract.%'
            OR BINARY rp.permission_code LIKE 'settlement.%'
            OR BINARY rp.permission_code IN (
                'agent.workflow.invoke.knowledge',
                'agent.workflow.invoke.business-review',
                'agent.workflow.invoke.platform-operations'))
    AND (SELECT COUNT(*) = 1 FROM bk_auth.auth_authorization_migration
         WHERE migration_id = '20261030_retire_platform_legacy_authorization'),
    1, 0
);
