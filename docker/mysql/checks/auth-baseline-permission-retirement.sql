SELECT IF(
    (SELECT COUNT(*) = 26 AND COALESCE(SUM(active <> 0), 0) = 0
     FROM auth_permission WHERE BINARY permission_code IN (
        'agent.chat.use', 'agent.workflow.read', 'agent.workflow.artifact.read',
        'listing.read', 'listing.create', 'listing.update.own',
        'customer.read', 'customer.create', 'customer.update.own',
        'customer.follow.read.own', 'customer.follow.write.own',
        'customer.favorite.read.own', 'customer.favorite.write.own', 'customer.match.own',
        'compare.report.create', 'compare.report.read.own', 'media.task.read.own',
        'notification.message.read.own', 'notification.message.mark-read.own',
        'interview.initiate', 'interview.read', 'interview.respond', 'interview.end',
        'memory.read', 'memory.write', 'memory.search'
    ))
    AND (SELECT COUNT(*) FROM auth_role_permission WHERE BINARY permission_code IN (
        'agent.chat.use', 'agent.workflow.read', 'agent.workflow.artifact.read',
        'listing.read', 'listing.create', 'listing.update.own',
        'customer.read', 'customer.create', 'customer.update.own',
        'customer.follow.read.own', 'customer.follow.write.own',
        'customer.favorite.read.own', 'customer.favorite.write.own', 'customer.match.own',
        'compare.report.create', 'compare.report.read.own', 'media.task.read.own',
        'notification.message.read.own', 'notification.message.mark-read.own',
        'interview.initiate', 'interview.read', 'interview.respond', 'interview.end',
        'memory.read', 'memory.write', 'memory.search'
    )) = 0
    AND (SELECT COUNT(*) FROM auth_authorization_migration
         WHERE migration_id = '20261028_baseline_permission_retirement') = 1,
    1, 0
);
