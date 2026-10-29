-- Register the explicit Knowledge and Business Review operations. No role grants are inferred.
USE bk_auth;

START TRANSACTION;
SELECT lock_id FROM auth_authorization_writer_lock WHERE lock_id = 1 FOR UPDATE;

SET @four_domain_catalog_migration = NOT EXISTS (
    SELECT 1 FROM auth_authorization_migration
    WHERE migration_id = '20261028_four_domain_authorization_catalog'
);

INSERT IGNORE INTO auth_permission (permission_code, domain, operation, active, version)
SELECT p.permission_code, p.domain, p.operation, 1, 1 FROM (
    SELECT 'agent.workflow.invoke.knowledge' permission_code, 'agent' domain, 'workflow.invoke.knowledge' operation
    UNION ALL SELECT 'agent.workflow.invoke.business-review', 'agent', 'workflow.invoke.business-review'
    UNION ALL SELECT 'knowledge.case.create.own', 'knowledge', 'case.create.own'
    UNION ALL SELECT 'knowledge.case.edit.own', 'knowledge', 'case.edit.own'
    UNION ALL SELECT 'knowledge.case.submit.own', 'knowledge', 'case.submit.own'
    UNION ALL SELECT 'knowledge.case.generate.own', 'knowledge', 'case.generate.own'
    UNION ALL SELECT 'knowledge.case.review.decide.own', 'knowledge', 'case.review.decide.own'
    UNION ALL SELECT 'knowledge.case.review.decide.store', 'knowledge', 'case.review.decide.store'
    UNION ALL SELECT 'knowledge.case.publish.own', 'knowledge', 'case.publish.own'
    UNION ALL SELECT 'knowledge.case.publish.store', 'knowledge', 'case.publish.store'
    UNION ALL SELECT 'knowledge.case.withdraw.own', 'knowledge', 'case.withdraw.own'
    UNION ALL SELECT 'knowledge.case.withdraw.store', 'knowledge', 'case.withdraw.store'
    UNION ALL SELECT 'knowledge.case.read.own', 'knowledge', 'case.read.own'
    UNION ALL SELECT 'knowledge.case.read.store', 'knowledge', 'case.read.store'
    UNION ALL SELECT 'business.review.create.store', 'business', 'review.create.store'
    UNION ALL SELECT 'business.review.read.store', 'business', 'review.read.store'
    UNION ALL SELECT 'business.review.plan.write.store', 'business', 'review.plan.write.store'
    UNION ALL SELECT 'business.review.plan.confirm.store', 'business', 'review.plan.confirm.store'
    UNION ALL SELECT 'business.review.action.feedback.store', 'business', 'review.action.feedback.store'
    UNION ALL SELECT 'business.review.observation.read.store', 'business', 'review.observation.read.store'
) p WHERE @four_domain_catalog_migration;

UPDATE auth_user_authorization_state
SET permission_revision = permission_revision + 1, updated_at = CURRENT_TIMESTAMP(6)
WHERE @four_domain_catalog_migration;

INSERT INTO auth_authorization_outbox (event_id, affected_users, revisions, reason)
SELECT CONCAT('four-domain-catalog-', s.user_id), CONCAT('[', s.user_id, ']'),
       JSON_ARRAY(JSON_OBJECT('userId', s.user_id, 'permissionRevision', s.permission_revision,
                              'credentialEpoch', s.credential_epoch, 'status', s.status)),
       'CATALOG_CHANGE'
FROM auth_user_authorization_state s WHERE @four_domain_catalog_migration;

INSERT INTO auth_authorization_migration (migration_id)
SELECT '20261028_four_domain_authorization_catalog' WHERE @four_domain_catalog_migration;
COMMIT;
