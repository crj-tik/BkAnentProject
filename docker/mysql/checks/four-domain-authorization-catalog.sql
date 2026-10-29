SELECT IF(
    (SELECT COUNT(*) FROM auth_permission
     WHERE active = 1 AND (
         (domain = 'agent' AND operation IN ('workflow.invoke.knowledge','workflow.invoke.business-review')
             AND permission_code = CONCAT('agent.', operation))
         OR (domain = 'knowledge' AND operation IN (
             'case.create.own','case.edit.own','case.submit.own','case.generate.own',
             'case.review.decide.own','case.review.decide.store','case.publish.own','case.publish.store',
             'case.withdraw.own','case.withdraw.store','case.read.own','case.read.store')
             AND permission_code = CONCAT('knowledge.', operation))
         OR (domain = 'business' AND operation IN (
             'review.create.store','review.read.store','review.plan.write.store','review.plan.confirm.store',
             'review.action.feedback.store','review.observation.read.store')
             AND permission_code = CONCAT('business.', operation))
     )) = 20
    AND EXISTS (SELECT 1 FROM auth_authorization_migration
                WHERE migration_id = '20261028_four_domain_authorization_catalog'),
    1, 0);
