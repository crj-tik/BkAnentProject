-- Register exact Business and Settlement operations. No ordinary role grants them by default.
USE bk_auth;

SET @business_settlement_authorization_migration = NOT EXISTS (
    SELECT 1 FROM auth_authorization_migration
    WHERE migration_id = '20261026_business_settlement_authorization'
);

START TRANSACTION;
SELECT lock_id FROM auth_authorization_writer_lock WHERE lock_id = 1 FOR UPDATE;

INSERT INTO auth_permission (permission_code, domain, operation, active, version)
SELECT permissions.permission_code, permissions.domain, permissions.operation, 1, 1 FROM (
    SELECT 'business.kpi.read.all' permission_code, 'business' domain, 'kpi.read.all' operation
    UNION ALL SELECT 'business.workload.read.all', 'business', 'workload.read.all'
    UNION ALL SELECT 'business.assessment.calculate.all', 'business', 'assessment.calculate.all'
    UNION ALL SELECT 'business.ranking.read.all', 'business', 'ranking.read.all'
    UNION ALL SELECT 'business.ranking.rebuild.all', 'business', 'ranking.rebuild.all'
    UNION ALL SELECT 'business.turnover.read.all', 'business', 'turnover.read.all'
    UNION ALL SELECT 'business.store-dashboard.read.all', 'business', 'store-dashboard.read.all'
    UNION ALL SELECT 'business.dashboard.read.all', 'business', 'dashboard.read.all'
    UNION ALL SELECT 'settlement.rule.read.all', 'settlement', 'rule.read.all'
    UNION ALL SELECT 'settlement.rule.write.all', 'settlement', 'rule.write.all'
    UNION ALL SELECT 'settlement.calculate.all', 'settlement', 'calculate.all'
    UNION ALL SELECT 'settlement.record.create.all', 'settlement', 'record.create.all'
    UNION ALL SELECT 'settlement.split.calculate.all', 'settlement', 'split.calculate.all'
    UNION ALL SELECT 'settlement.generate.all', 'settlement', 'generate.all'
    UNION ALL SELECT 'settlement.record.read.all', 'settlement', 'record.read.all'
    UNION ALL SELECT 'settlement.split.read.all', 'settlement', 'split.read.all'
    UNION ALL SELECT 'settlement.payout-status.update.all', 'settlement', 'payout-status.update.all'
    UNION ALL SELECT 'settlement.summary.read.all', 'settlement', 'summary.read.all'
    UNION ALL SELECT 'settlement.summary.rebuild.all', 'settlement', 'summary.rebuild.all'
    UNION ALL SELECT 'settlement.batch.create.all', 'settlement', 'batch.create.all'
    UNION ALL SELECT 'settlement.commission.read.all', 'settlement', 'commission.read.all'
) permissions
WHERE @business_settlement_authorization_migration
ON DUPLICATE KEY UPDATE active = 1, version = version + 1;

UPDATE auth_user_authorization_state
SET permission_revision = permission_revision + 1,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE @business_settlement_authorization_migration;

INSERT INTO auth_authorization_outbox (event_id, affected_users, revisions, reason)
SELECT CONCAT('business-settlement-auth-', s.user_id),
       CONCAT('[', s.user_id, ']'),
       JSON_ARRAY(JSON_OBJECT('userId', s.user_id,
                              'permissionRevision', s.permission_revision,
                              'credentialEpoch', s.credential_epoch,
                              'status', s.status)),
       'CATALOG_CHANGE'
FROM auth_user_authorization_state s
WHERE @business_settlement_authorization_migration;

INSERT INTO auth_authorization_migration (migration_id)
SELECT '20261026_business_settlement_authorization'
WHERE @business_settlement_authorization_migration;
COMMIT;
