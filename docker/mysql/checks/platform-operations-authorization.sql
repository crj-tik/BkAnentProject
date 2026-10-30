SELECT IF(
    (SELECT COUNT(*)=4 AND COALESCE(SUM(active=1),0)=4 FROM bk_auth.auth_permission
     WHERE permission_code IN ('agent.workflow.invoke.platform-operations',
         'agent.workflow.invoke.platform-operations.analytics',
         'agent.workflow.invoke.platform-operations.review',
         'agent.workflow.invoke.platform-operations.knowledge') AND domain='agent'
         AND permission_code=CONCAT('agent.',operation))
    AND (SELECT COUNT(*)=1 FROM bk_auth.auth_authorization_migration
         WHERE migration_id='20261029_platform_operations_authorization'),
    1, 0
);
