-- Organization data only. Existing account, role and authorization grants are unchanged.
USE bk_auth;

CREATE TABLE IF NOT EXISTS auth_org_unit (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    org_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    org_name VARCHAR(128) NOT NULL,
    org_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    parent_id BIGINT NULL,
    store_business_id BIGINT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
    revision BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_auth_org_code (org_code),
    UNIQUE KEY uk_auth_org_store_business (store_business_id),
    KEY idx_auth_org_parent (parent_id, status),
    CONSTRAINT fk_auth_org_parent FOREIGN KEY (parent_id) REFERENCES auth_org_unit (id),
    CONSTRAINT chk_auth_org_type CHECK (org_type IN ('COMPANY', 'STORE', 'DEPARTMENT')),
    CONSTRAINT chk_auth_org_parent CHECK ((org_type = 'COMPANY' AND parent_id IS NULL)
        OR (org_type IN ('STORE', 'DEPARTMENT') AND parent_id IS NOT NULL)),
    CONSTRAINT chk_auth_org_store_id CHECK (store_business_id IS NULL
        OR (org_type = 'STORE' AND store_business_id > 0)),
    CONSTRAINT chk_auth_org_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS auth_org_membership (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    org_id BIGINT NOT NULL,
    is_primary TINYINT NOT NULL DEFAULT 0,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
    valid_from DATETIME(6) NULL,
    valid_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    KEY idx_auth_org_member_user (user_id, status, valid_from, valid_until),
    KEY idx_auth_org_member_org (org_id, status, user_id),
    CONSTRAINT fk_auth_org_member_user FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT fk_auth_org_member_org FOREIGN KEY (org_id) REFERENCES auth_org_unit (id),
    CONSTRAINT chk_auth_org_member_primary CHECK (is_primary IN (0, 1)),
    CONSTRAINT chk_auth_org_member_status CHECK (status IN ('ACTIVE', 'ENDED')),
    CONSTRAINT chk_auth_org_member_interval CHECK (valid_from IS NULL OR valid_until IS NULL
        OR valid_from < valid_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Membership intervals and single current primary assignment must be serialized by
-- auth_authorization_writer_lock; a static unique key cannot express time intervals.
