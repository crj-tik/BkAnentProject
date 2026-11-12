-- Durable idempotency ledger for future/expiry membership boundaries.
USE bk_auth;

CREATE TABLE IF NOT EXISTS auth_authorization_membership_boundary (
    membership_id BIGINT NOT NULL,
    boundary_kind VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    boundary_at DATETIME(6) NOT NULL,
    processed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (membership_id, boundary_kind, boundary_at),
    KEY idx_auth_org_boundary_due (boundary_at, membership_id),
    CONSTRAINT fk_auth_org_boundary_membership FOREIGN KEY (membership_id)
        REFERENCES auth_org_membership (id),
    CONSTRAINT chk_auth_org_boundary_kind CHECK (boundary_kind IN ('START', 'END'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
