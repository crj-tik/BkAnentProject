-- Additive migration following frozen 20261023_versioned_authorization.sql.
USE bk_auth;

-- Digest, not token/session identity. Acceptance invalidates old user runs once;
-- only the submitted token is revoked. PENDING remains denied on every token parse.
CREATE TABLE IF NOT EXISTS auth_account_logout_event (
    token_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    KEY idx_auth_logout_pending (state, expires_at),
    KEY idx_auth_logout_user (user_id)
) ENGINE=InnoDB;
