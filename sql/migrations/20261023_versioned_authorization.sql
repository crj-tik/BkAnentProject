-- Incremental MySQL 8 migration. No user/unknown-role data is deleted or inferred.
-- Fresh databases are supported without importing demo users or passwords.
CREATE DATABASE IF NOT EXISTS bk_auth DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE bk_auth;

CREATE TABLE IF NOT EXISTS user_account (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(128) NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    role_code VARCHAR(32) NOT NULL,
    tenant_code VARCHAR(64) NOT NULL,
    account_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS auth_role (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    role_code VARCHAR(32) NOT NULL UNIQUE,
    role_name VARCHAR(64) NOT NULL,
    description VARCHAR(255) NULL,
    active TINYINT NOT NULL DEFAULT 1,
    assignable TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SELECT COUNT(*) INTO @authz_admin_exists FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'user_account' AND column_name = 'business_admin';
SET @authz_admin_sql = IF(@authz_admin_exists = 0,
    'ALTER TABLE user_account ADD COLUMN business_admin TINYINT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE authz_admin_stmt FROM @authz_admin_sql;
EXECUTE authz_admin_stmt;
DEALLOCATE PREPARE authz_admin_stmt;

CREATE TABLE IF NOT EXISTS auth_permission (
    permission_code VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    domain VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    active TINYINT NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS auth_role_permission (
    role_id BIGINT NOT NULL,
    permission_code VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (role_id, permission_code),
    KEY idx_auth_role_permission_code (permission_code)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS auth_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    valid_from DATETIME(6) NULL,
    valid_until DATETIME(6) NULL,
    active TINYINT NOT NULL DEFAULT 1,
    PRIMARY KEY (user_id, role_id),
    KEY idx_auth_user_role_role (role_id, user_id),
    KEY idx_auth_user_role_expiry (active, valid_until)
) ENGINE=InnoDB;

-- Accommodate a pre-existing association table without touching its data.
SELECT COUNT(*) INTO @authz_from_exists FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'auth_user_role' AND column_name = 'valid_from';
SET @authz_from_sql = IF(@authz_from_exists = 0,
    'ALTER TABLE auth_user_role ADD COLUMN valid_from DATETIME(6) NULL', 'SELECT 1');
PREPARE authz_from_stmt FROM @authz_from_sql;
EXECUTE authz_from_stmt;
DEALLOCATE PREPARE authz_from_stmt;
SELECT COUNT(*) INTO @authz_until_exists FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'auth_user_role' AND column_name = 'valid_until';
SET @authz_until_sql = IF(@authz_until_exists = 0,
    'ALTER TABLE auth_user_role ADD COLUMN valid_until DATETIME(6) NULL', 'SELECT 1');
PREPARE authz_until_stmt FROM @authz_until_sql;
EXECUTE authz_until_stmt;
DEALLOCATE PREPARE authz_until_stmt;
SELECT COUNT(*) INTO @authz_active_exists FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'auth_user_role' AND column_name = 'active';
SET @authz_active_sql = IF(@authz_active_exists = 0,
    'ALTER TABLE auth_user_role ADD COLUMN active TINYINT NOT NULL DEFAULT 1', 'SELECT 1');
PREPARE authz_active_stmt FROM @authz_active_sql;
EXECUTE authz_active_stmt;
DEALLOCATE PREPARE authz_active_stmt;

CREATE TABLE IF NOT EXISTS auth_user_authorization_state (
    user_id BIGINT NOT NULL PRIMARY KEY,
    permission_revision BIGINT NOT NULL DEFAULT 1,
    credential_epoch BIGINT NOT NULL DEFAULT 1,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS auth_authorization_outbox (
    event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    affected_users LONGTEXT NOT NULL,
    revisions LONGTEXT NOT NULL,
    reason VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    committed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    lease_epoch BIGINT NOT NULL DEFAULT 0,
    lease_until DATETIME(6) NULL,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_authz_outbox_claim (status, next_attempt_at, lease_until, committed_at)
) ENGINE=InnoDB;

-- Shared RPC replay fence; unique caller/key/nonce, hashes only and bounded expiry.
CREATE TABLE IF NOT EXISTS auth_authorization_nonce (
    caller VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    key_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    nonce_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (caller, key_id, nonce_hash),
    KEY idx_authz_nonce_expiry (expires_at)
) ENGINE=InnoDB;

-- Boundary identities survive restarts; a replaced grant resets its own boundary markers atomically.
CREATE TABLE IF NOT EXISTS auth_authorization_grant_boundary (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    boundary_kind VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    boundary_at DATETIME(6) NOT NULL,
    processed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, role_id, boundary_kind, boundary_at)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS auth_authorization_audit (
    audit_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    actor_id BIGINT NOT NULL,
    actor_revision BIGINT NOT NULL,
    action VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id BIGINT NOT NULL,
    event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;

-- All new writer paths take this mutex before discovering affected users, then lock userIds ascending.
-- This prevents role/grant/catalog writer races and phantom affected users in the initial small deployment.
CREATE TABLE IF NOT EXISTS auth_authorization_writer_lock (
    lock_id TINYINT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
INSERT IGNORE INTO auth_authorization_writer_lock (lock_id) VALUES (1);
CREATE TABLE IF NOT EXISTS auth_authorization_migration (
    migration_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    applied_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;

-- One-time data migration. Repeat runs must NOT resurrect revoked roles/admin flags or disabled permissions.
START TRANSACTION;
SELECT lock_id FROM auth_authorization_writer_lock WHERE lock_id = 1 FOR UPDATE;
SET @authz_seed = NOT EXISTS (SELECT 1 FROM auth_authorization_migration
    WHERE migration_id = '20261023_versioned_authorization');
INSERT INTO auth_role (role_code, role_name, active, assignable)
SELECT 'ADMIN', '系统管理员', 1, 1 WHERE @authz_seed AND NOT EXISTS
    (SELECT 1 FROM auth_role WHERE role_code = 'ADMIN');
INSERT INTO auth_role (role_code, role_name, active, assignable)
SELECT 'BROKER', '经纪人', 1, 1 WHERE @authz_seed AND NOT EXISTS
    (SELECT 1 FROM auth_role WHERE role_code = 'BROKER');

-- Explicit core contracts only; no generic MCP server/prefix permission or unverified domain invocation.
INSERT IGNORE INTO auth_permission (permission_code, domain, operation, active, version)
SELECT permission_code, domain, operation, 1, 1 FROM (
    SELECT 'agent.chat.use' permission_code, 'agent' domain, 'chat.use' operation
    UNION ALL SELECT 'agent.workflow.read', 'agent', 'workflow.read'
    UNION ALL SELECT 'agent.workflow.artifact.read', 'agent', 'workflow.artifact.read'
    UNION ALL SELECT 'agent.workflow.invoke.interview', 'agent', 'workflow.invoke.interview'
    UNION ALL SELECT 'agent.workflow.invoke.memory', 'agent', 'workflow.invoke.memory'
    UNION ALL SELECT 'agent.governance.read', 'agent', 'governance.read'
    UNION ALL SELECT 'agent.governance.manage', 'agent', 'governance.manage'
    UNION ALL SELECT 'agent.audit.read', 'agent', 'audit.read'
    UNION ALL SELECT 'agent.audit.manage', 'agent', 'audit.manage'
    UNION ALL SELECT 'agent.rag.read', 'agent', 'rag.read'
    UNION ALL SELECT 'agent.rag.search', 'agent', 'rag.search'
    UNION ALL SELECT 'agent.rag.manage', 'agent', 'rag.manage'
    UNION ALL SELECT 'agent.vector-store.read', 'agent', 'vector-store.read'
    UNION ALL SELECT 'agent.vector-store.manage', 'agent', 'vector-store.manage'
    UNION ALL SELECT 'agent.rag.memory.search', 'agent', 'rag.memory.search'
    UNION ALL SELECT 'agent.rag.listing.query', 'agent', 'rag.listing.query'
    UNION ALL SELECT 'rag.space.manage', 'rag', 'space.manage'
    UNION ALL SELECT 'listing.read', 'listing', 'read'
    UNION ALL SELECT 'interview.initiate', 'interview', 'initiate'
    UNION ALL SELECT 'interview.read', 'interview', 'read'
    UNION ALL SELECT 'interview.respond', 'interview', 'respond'
    UNION ALL SELECT 'interview.assist', 'interview', 'assist'
    UNION ALL SELECT 'interview.end', 'interview', 'end'
    UNION ALL SELECT 'interview.report.generate', 'interview', 'report.generate'
    UNION ALL SELECT 'interview.asset.submit', 'interview', 'asset.submit'
    UNION ALL SELECT 'interview.asset.publish', 'interview', 'asset.publish'
    UNION ALL SELECT 'interview.asset.regrade', 'interview', 'asset.regrade'
    UNION ALL SELECT 'memory.read', 'memory', 'read'
    UNION ALL SELECT 'memory.write', 'memory', 'write'
    UNION ALL SELECT 'memory.search', 'memory', 'search'
    UNION ALL SELECT 'memory.share', 'memory', 'share'
    UNION ALL SELECT 'memory.revoke', 'memory', 'revoke'
    UNION ALL SELECT 'memory.delete', 'memory', 'delete'
    UNION ALL SELECT 'memory.constraint.read', 'memory', 'constraint.read'
    UNION ALL SELECT 'memory.constraint.manage', 'memory', 'constraint.manage'
    UNION ALL SELECT 'memory.experience.governance.publish', 'memory', 'experience.governance.publish'
    UNION ALL SELECT 'auth.users.manage', 'auth', 'users.manage'
    UNION ALL SELECT 'auth.roles.manage', 'auth', 'roles.manage'
) contracts WHERE @authz_seed;

INSERT IGNORE INTO auth_role_permission (role_id, permission_code)
SELECT r.id, p.permission_code FROM auth_role r CROSS JOIN auth_permission p
WHERE @authz_seed AND BINARY r.role_code = BINARY 'BROKER' AND p.permission_code IN (
    'agent.chat.use', 'agent.workflow.read', 'agent.workflow.artifact.read',
    'agent.workflow.invoke.interview', 'agent.workflow.invoke.memory',
    'agent.rag.read', 'agent.rag.search', 'agent.vector-store.read',
    'agent.rag.memory.search', 'agent.rag.listing.query', 'listing.read',
    'interview.initiate', 'interview.read', 'interview.respond', 'interview.assist',
    'interview.end', 'interview.report.generate', 'interview.asset.submit',
    'memory.read', 'memory.write', 'memory.search', 'memory.share', 'memory.revoke',
    'memory.delete', 'memory.constraint.read');
-- ADMIN has no role wildcard; only the one-time trusted account mapping sets business_admin.
UPDATE user_account u JOIN auth_role r ON BINARY r.role_code = BINARY u.role_code
SET u.business_admin = 1
WHERE @authz_seed AND BINARY u.role_code = BINARY 'ADMIN' AND r.active = 1 AND r.deleted = 0;
INSERT IGNORE INTO auth_user_role (user_id, role_id, active)
SELECT u.id, r.id, 1 FROM user_account u JOIN auth_role r ON BINARY r.role_code = BINARY u.role_code
WHERE @authz_seed AND BINARY u.role_code IN ('ADMIN', 'BROKER');
-- Unknown legacy role rows are preserved, but never receive inferred mappings or permissions.
INSERT IGNORE INTO auth_user_authorization_state (user_id, permission_revision, credential_epoch, status)
SELECT id, 1, 1, CASE WHEN deleted <> 0 THEN 'DELETED'
    WHEN account_status = 'ACTIVE' THEN 'ACTIVE' ELSE 'DISABLED' END
FROM user_account WHERE @authz_seed;
INSERT INTO auth_authorization_outbox (event_id, affected_users, revisions, reason)
SELECT CONCAT('migration-20261023-', user_id), CONCAT('[', user_id, ']'),
    CONCAT('[{"userId":', user_id, ',"permissionRevision":', permission_revision,
           ',"credentialEpoch":', credential_epoch, ',"status":"', status, '"}]'), 'MIGRATION'
FROM auth_user_authorization_state WHERE @authz_seed;
INSERT IGNORE INTO auth_authorization_migration (migration_id)
VALUES ('20261023_versioned_authorization');
COMMIT;
