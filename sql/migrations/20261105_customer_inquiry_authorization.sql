-- Buyer listing inquiry operations (implement-internal-listing-business).
-- Registers inquiry read/write baseline operations in the customer domain and grants them to BROKER.
USE bk_auth;

SET @inquiry_permission_migration = NOT EXISTS (
    SELECT 1 FROM auth_authorization_migration
    WHERE migration_id = '20261105_customer_inquiry_authorization'
);

START TRANSACTION;
SELECT lock_id FROM auth_authorization_writer_lock WHERE lock_id = 1 FOR UPDATE;

INSERT INTO auth_permission (permission_code, domain, operation, active, version)
SELECT contracts.permission_code, contracts.domain, contracts.operation, 1, 1 FROM (
    SELECT 'customer.inquiry.read.own' permission_code, 'customer' domain, 'inquiry.read.own' operation
    UNION ALL SELECT 'customer.inquiry.read.all', 'customer', 'inquiry.read.all'
    UNION ALL SELECT 'customer.inquiry.write.own', 'customer', 'inquiry.write.own'
    UNION ALL SELECT 'customer.inquiry.write.all', 'customer', 'inquiry.write.all'
) contracts WHERE @inquiry_permission_migration
ON DUPLICATE KEY UPDATE active = 1, version = version + 1;

INSERT IGNORE INTO auth_role_permission(role_id, permission_code)
SELECT r.id, p.permission_code FROM auth_role r
JOIN auth_permission p ON p.domain = 'customer' AND p.active = 1
WHERE @inquiry_permission_migration AND BINARY r.role_code = BINARY 'BROKER'
  AND p.permission_code IN ('customer.inquiry.read.own', 'customer.inquiry.write.own');

UPDATE auth_user_authorization_state
SET permission_revision = permission_revision + 1,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE @inquiry_permission_migration;

INSERT INTO auth_authorization_outbox (event_id, affected_users, revisions, reason)
SELECT CONCAT('customer-inquiry-auth-', s.user_id),
       CONCAT('[', s.user_id, ']'),
       JSON_ARRAY(JSON_OBJECT('userId', s.user_id,
                              'permissionRevision', s.permission_revision,
                              'credentialEpoch', s.credential_epoch,
                              'status', s.status)),
       'CATALOG_CHANGE'
FROM auth_user_authorization_state s
WHERE @inquiry_permission_migration;

INSERT INTO auth_authorization_migration (migration_id)
SELECT '20261105_customer_inquiry_authorization' WHERE @inquiry_permission_migration;
COMMIT;
