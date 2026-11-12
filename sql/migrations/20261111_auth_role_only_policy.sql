-- Controls the final compatibility cutover after every active legacy administrator is migrated.
USE bk_auth;

CREATE TABLE IF NOT EXISTS auth_authorization_policy (
    policy_id TINYINT NOT NULL PRIMARY KEY,
    role_only_mode TINYINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_auth_authorization_policy_singleton CHECK (policy_id = 1),
    CONSTRAINT chk_auth_authorization_policy_mode CHECK (role_only_mode IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO auth_authorization_policy(policy_id,role_only_mode) VALUES (1,0)
ON DUPLICATE KEY UPDATE policy_id=VALUES(policy_id);
