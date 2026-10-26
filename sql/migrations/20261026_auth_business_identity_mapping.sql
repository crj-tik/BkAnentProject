-- Auth owns explicit user-to-business identity mappings; no numeric ids are inferred.
USE bk_auth;

CREATE TABLE IF NOT EXISTS auth_user_business_identity (
    user_id BIGINT NOT NULL,
    identity_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    identity_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, identity_type),
    UNIQUE KEY uk_auth_business_identity_type_id (identity_type, identity_id),
    KEY idx_auth_business_identity_id_user (identity_id, user_id)
) ENGINE=InnoDB;

SET @business_identity_migration = NOT EXISTS (
    SELECT 1 FROM auth_authorization_migration
    WHERE migration_id = '20261026_auth_business_identity_mapping'
);

START TRANSACTION;
SELECT lock_id FROM auth_authorization_writer_lock WHERE lock_id = 1 FOR UPDATE;

INSERT INTO auth_permission (permission_code, domain, operation, active, version)
SELECT 'auth.business-identity.manage', 'auth', 'business-identity.manage', 1, 1
WHERE @business_identity_migration
ON DUPLICATE KEY UPDATE active = 1, version = version + 1;

INSERT INTO auth_permission (permission_code, domain, operation, active, version)
SELECT contracts.permission_code, contracts.domain, contracts.operation, 1, 1 FROM (
    SELECT 'listing.read.all' permission_code, 'listing' domain, 'read.all' operation
    UNION ALL SELECT 'listing.create', 'listing', 'create'
    UNION ALL SELECT 'listing.update.own', 'listing', 'update.own'
    UNION ALL SELECT 'listing.update.all', 'listing', 'update.all'
    UNION ALL SELECT 'listing.delete.own', 'listing', 'delete.own'
    UNION ALL SELECT 'listing.delete.all', 'listing', 'delete.all'
    UNION ALL SELECT 'listing.status.update.own', 'listing', 'status.update.own'
    UNION ALL SELECT 'listing.status.update.all', 'listing', 'status.update.all'
    UNION ALL SELECT 'listing.assets.bind.own', 'listing', 'assets.bind.own'
    UNION ALL SELECT 'listing.assets.bind.all', 'listing', 'assets.bind.all'
    UNION ALL SELECT 'listing.ocr.own', 'listing', 'ocr.own'
    UNION ALL SELECT 'listing.ocr.all', 'listing', 'ocr.all'
    UNION ALL SELECT 'listing.verify.own', 'listing', 'verify.own'
    UNION ALL SELECT 'listing.verify.all', 'listing', 'verify.all'
    UNION ALL SELECT 'listing.index.manage', 'listing', 'index.manage'
    UNION ALL SELECT 'customer.read', 'customer', 'read'
    UNION ALL SELECT 'customer.read.all', 'customer', 'read.all'
    UNION ALL SELECT 'customer.create', 'customer', 'create'
    UNION ALL SELECT 'customer.update.own', 'customer', 'update.own'
    UNION ALL SELECT 'customer.update.all', 'customer', 'update.all'
    UNION ALL SELECT 'customer.delete.own', 'customer', 'delete.own'
    UNION ALL SELECT 'customer.delete.all', 'customer', 'delete.all'
    UNION ALL SELECT 'customer.follow.read.own', 'customer', 'follow.read.own'
    UNION ALL SELECT 'customer.follow.read.all', 'customer', 'follow.read.all'
    UNION ALL SELECT 'customer.follow.write.own', 'customer', 'follow.write.own'
    UNION ALL SELECT 'customer.follow.write.all', 'customer', 'follow.write.all'
    UNION ALL SELECT 'customer.entrust.read.own', 'customer', 'entrust.read.own'
    UNION ALL SELECT 'customer.entrust.read.all', 'customer', 'entrust.read.all'
    UNION ALL SELECT 'customer.entrust.write.own', 'customer', 'entrust.write.own'
    UNION ALL SELECT 'customer.entrust.write.all', 'customer', 'entrust.write.all'
    UNION ALL SELECT 'customer.favorite.read.own', 'customer', 'favorite.read.own'
    UNION ALL SELECT 'customer.favorite.read.all', 'customer', 'favorite.read.all'
    UNION ALL SELECT 'customer.favorite.write.own', 'customer', 'favorite.write.own'
    UNION ALL SELECT 'customer.favorite.write.all', 'customer', 'favorite.write.all'
    UNION ALL SELECT 'customer.match.own', 'customer', 'match.own'
    UNION ALL SELECT 'customer.match.all', 'customer', 'match.all'
    UNION ALL SELECT 'customer.entrust.expiring.read', 'customer', 'entrust.expiring.read'
    UNION ALL SELECT 'customer.reminder.send', 'customer', 'reminder.send'
) contracts WHERE @business_identity_migration
ON DUPLICATE KEY UPDATE active = 1, version = version + 1;

INSERT IGNORE INTO auth_role_permission(role_id, permission_code)
SELECT r.id, p.permission_code FROM auth_role r
JOIN auth_permission p ON p.domain IN ('listing','customer') AND p.active=1
WHERE @business_identity_migration AND BINARY r.role_code=BINARY 'BROKER'
  AND p.permission_code IN (
    'listing.read','listing.create','listing.update.own','listing.delete.own',
    'listing.status.update.own','listing.assets.bind.own','listing.ocr.own','listing.verify.own',
    'customer.read','customer.create','customer.update.own','customer.delete.own',
    'customer.follow.read.own','customer.follow.write.own',
    'customer.entrust.read.own','customer.entrust.write.own',
    'customer.favorite.read.own','customer.favorite.write.own','customer.match.own'
  );

UPDATE auth_user_authorization_state
SET permission_revision = permission_revision + 1,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE @business_identity_migration;

INSERT INTO auth_authorization_outbox (event_id, affected_users, revisions, reason)
SELECT CONCAT('business-identity-map-', s.user_id),
       CONCAT('[', s.user_id, ']'),
       JSON_ARRAY(JSON_OBJECT('userId', s.user_id,
                              'permissionRevision', s.permission_revision,
                              'credentialEpoch', s.credential_epoch,
                              'status', s.status)),
       'CATALOG_CHANGE'
FROM auth_user_authorization_state s
WHERE @business_identity_migration;

INSERT INTO auth_authorization_migration (migration_id)
SELECT '20261026_auth_business_identity_mapping' WHERE @business_identity_migration;
COMMIT;
