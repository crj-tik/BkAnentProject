-- Register exact Contract, Marketing and Promotion operations. No role grants are inferred.
USE bk_auth;

SET @contract_marketing_authorization_migration = NOT EXISTS (
    SELECT 1 FROM auth_authorization_migration
    WHERE migration_id = '20261026_contract_marketing_authorization'
);

START TRANSACTION;
SELECT lock_id FROM auth_authorization_writer_lock WHERE lock_id = 1 FOR UPDATE;

INSERT INTO auth_permission (permission_code, domain, operation, active, version)
SELECT permissions.permission_code, permissions.domain, permissions.operation, 1, 1 FROM (
    SELECT 'contract.template.read.all' permission_code, 'contract' domain, 'template.read.all' operation
    UNION ALL SELECT 'contract.template.write.all', 'contract', 'template.write.all'
    UNION ALL SELECT 'contract.create.own', 'contract', 'create.own'
    UNION ALL SELECT 'contract.read.own', 'contract', 'read.own'
    UNION ALL SELECT 'contract.read.all', 'contract', 'read.all'
    UNION ALL SELECT 'contract.status.update.own', 'contract', 'status.update.own'
    UNION ALL SELECT 'contract.status.update.all', 'contract', 'status.update.all'
    UNION ALL SELECT 'contract.attachment.ocr.own', 'contract', 'attachment.ocr.own'
    UNION ALL SELECT 'contract.attachment.ocr.all', 'contract', 'attachment.ocr.all'
    UNION ALL SELECT 'contract.archive.own', 'contract', 'archive.own'
    UNION ALL SELECT 'contract.archive.all', 'contract', 'archive.all'
    UNION ALL SELECT 'contract.seal.own', 'contract', 'seal.own'
    UNION ALL SELECT 'contract.seal.all', 'contract', 'seal.all'
    UNION ALL SELECT 'contract.risk.read.own', 'contract', 'risk.read.own'
    UNION ALL SELECT 'contract.risk.read.all', 'contract', 'risk.read.all'
    UNION ALL SELECT 'contract.settlement.read.all', 'contract', 'settlement.read.all'
    UNION ALL SELECT 'contract.reminder.send.all', 'contract', 'reminder.send.all'
    UNION ALL SELECT 'marketing.content.create.own', 'marketing', 'content.create.own'
    UNION ALL SELECT 'marketing.content.read.own', 'marketing', 'content.read.own'
    UNION ALL SELECT 'marketing.content.read.all', 'marketing', 'content.read.all'
    UNION ALL SELECT 'marketing.content.variant.create.own', 'marketing', 'content.variant.create.own'
    UNION ALL SELECT 'marketing.content.variant.create.all', 'marketing', 'content.variant.create.all'
    UNION ALL SELECT 'marketing.assets.bind.own', 'marketing', 'assets.bind.own'
    UNION ALL SELECT 'marketing.assets.bind.all', 'marketing', 'assets.bind.all'
    UNION ALL SELECT 'marketing.publish.status.update.own', 'marketing', 'publish.status.update.own'
    UNION ALL SELECT 'marketing.publish.status.update.all', 'marketing', 'publish.status.update.all'
    UNION ALL SELECT 'marketing.publish.execute.own', 'marketing', 'publish.execute.own'
    UNION ALL SELECT 'marketing.publish.execute.all', 'marketing', 'publish.execute.all'
    UNION ALL SELECT 'marketing.promotion.effect.read.all', 'marketing', 'promotion.effect.read.all'
    UNION ALL SELECT 'marketing.promotion.effect.sync.all', 'marketing', 'promotion.effect.sync.all'
    UNION ALL SELECT 'marketing.promotion.roi.read.all', 'marketing', 'promotion.roi.read.all'
    UNION ALL SELECT 'marketing.brand-assets.read.all', 'marketing', 'brand-assets.read.all'
    UNION ALL SELECT 'marketing.brand-assets.write.all', 'marketing', 'brand-assets.write.all'
) permissions
WHERE @contract_marketing_authorization_migration
ON DUPLICATE KEY UPDATE active = 1, version = version + 1;

UPDATE auth_user_authorization_state
SET permission_revision = permission_revision + 1,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE @contract_marketing_authorization_migration;

INSERT INTO auth_authorization_outbox (event_id, affected_users, revisions, reason)
SELECT CONCAT('contract-marketing-auth-', s.user_id),
       CONCAT('[', s.user_id, ']'),
       JSON_ARRAY(JSON_OBJECT('userId', s.user_id,
                              'permissionRevision', s.permission_revision,
                              'credentialEpoch', s.credential_epoch,
                              'status', s.status)),
       'CATALOG_CHANGE'
FROM auth_user_authorization_state s
WHERE @contract_marketing_authorization_migration;

INSERT INTO auth_authorization_migration (migration_id)
SELECT '20261026_contract_marketing_authorization'
WHERE @contract_marketing_authorization_migration;
COMMIT;
