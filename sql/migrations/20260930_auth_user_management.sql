USE bk_auth;

CREATE TABLE IF NOT EXISTS auth_role (
    id BIGINT NOT NULL AUTO_INCREMENT,
    role_code VARCHAR(32) NOT NULL,
    role_name VARCHAR(64) NOT NULL,
    description VARCHAR(255) NULL,
    active TINYINT NOT NULL DEFAULT 1,
    assignable TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_auth_role_role_code (role_code),
    KEY idx_auth_role_active (active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO auth_role (role_code, role_name, description, active, assignable)
SELECT 'ADMIN', '系统管理员', '维护用户、角色和系统账户', 1, 1
WHERE NOT EXISTS (SELECT 1 FROM auth_role WHERE role_code = 'ADMIN');
INSERT INTO auth_role (role_code, role_name, description, active, assignable)
SELECT 'MANAGER', '门店经理', '查看门店经营数据并管理业务协作', 1, 1
WHERE NOT EXISTS (SELECT 1 FROM auth_role WHERE role_code = 'MANAGER');
INSERT INTO auth_role (role_code, role_name, description, active, assignable)
SELECT 'BROKER', '经纪人', '处理房源、客户和智能助手任务', 1, 1
WHERE NOT EXISTS (SELECT 1 FROM auth_role WHERE role_code = 'BROKER');

INSERT INTO user_account (username, password_hash, display_name, role_code, tenant_code, account_status)
SELECT 'admin01', '$2a$10$ZmOp25bIZbbJLus780rqTuVZff7jjdcmJ4VqhXgio/t073Q3b5AXC', '系统管理员', 'ADMIN', 'local-demo', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM user_account WHERE username = 'admin01');
