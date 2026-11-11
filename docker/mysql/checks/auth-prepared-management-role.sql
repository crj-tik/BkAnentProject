SELECT
    (SELECT COUNT(*) FROM bk_auth.auth_role WHERE role_code='PLATFORM_ADMIN'
       AND role_type='MANAGEMENT' AND active=1 AND assignable=0 AND deleted=0) = 1
    AND (SELECT COUNT(*) FROM bk_auth.auth_user_role ur JOIN bk_auth.auth_role r ON r.id=ur.role_id
         WHERE r.role_code='PLATFORM_ADMIN') = 0
    AND NOT EXISTS (
      SELECT 1 FROM bk_auth.auth_permission p WHERE p.active=1 AND p.permission_code NOT IN (
        'agent.chat.use','agent.workflow.read','agent.workflow.artifact.read',
        'listing.read','listing.create','listing.update.own',
        'customer.read','customer.create','customer.update.own',
        'customer.follow.read.own','customer.follow.write.own',
        'customer.favorite.read.own','customer.favorite.write.own','customer.match.own',
        'compare.report.create','compare.report.read.own','media.task.read.own',
        'notification.message.read.own','notification.message.mark-read.own',
        'interview.initiate','interview.read','interview.respond','interview.end',
        'memory.read','memory.write','memory.search')
      AND NOT EXISTS (SELECT 1 FROM bk_auth.auth_role_permission rp JOIN bk_auth.auth_role r ON r.id=rp.role_id
                      WHERE r.role_code='PLATFORM_ADMIN' AND rp.permission_code=p.permission_code)
    )
    AND (SELECT COUNT(*) FROM bk_auth.auth_authorization_migration
         WHERE migration_id='20261111_prepare_management_role') = 1;
