CREATE DATABASE IF NOT EXISTS bk_auth DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS bk_listing DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS bk_customer DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS bk_marketing DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS bk_business DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS bk_contract DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS bk_settlement DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE bk_auth;
CREATE TABLE IF NOT EXISTS user_account (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(128) NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    role_code VARCHAR(32) NOT NULL,
    tenant_code VARCHAR(64) NOT NULL,
    account_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0
);

INSERT INTO user_account (username, password_hash, display_name, role_code, tenant_code, account_status)
VALUES ('broker01', 'plain-demo-password', 'Broker Demo', 'BROKER', 'store-shanghai-001', 'ACTIVE')
ON DUPLICATE KEY UPDATE updated_at = CURRENT_TIMESTAMP;

USE bk_listing;
CREATE TABLE IF NOT EXISTS listing_info (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(128) NOT NULL,
    address VARCHAR(255) NOT NULL,
    layout VARCHAR(32) NOT NULL,
    area DECIMAL(10,2) NOT NULL,
    total_price DECIMAL(12,2) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0
);

INSERT INTO listing_info (title, address, layout, area, total_price, status)
VALUES ('Pudong Garden 3BR', 'Shanghai Pudong Zhangjiang', '3B2B', 89.50, 680.00, 'ON_SALE');

USE bk_customer;
CREATE TABLE IF NOT EXISTS customer_profile (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(64) NOT NULL,
    mobile VARCHAR(32) NOT NULL,
    intention VARCHAR(64) NOT NULL,
    budget_min DECIMAL(12,2) NOT NULL,
    budget_max DECIMAL(12,2) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0
);

INSERT INTO customer_profile (name, mobile, intention, budget_min, budget_max)
VALUES ('Wang Buyer', '13800000000', 'upgrade-home', 500.00, 800.00);

USE bk_marketing;
CREATE TABLE IF NOT EXISTS marketing_content (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    listing_id BIGINT NOT NULL,
    platform VARCHAR(32) NOT NULL,
    copywriting TEXT NOT NULL,
    asset_urls TEXT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0
);

USE bk_business;
CREATE TABLE IF NOT EXISTS employee_kpi_stat (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    employee_id BIGINT NOT NULL,
    employee_name VARCHAR(64) NOT NULL,
    stat_month VARCHAR(16) NOT NULL,
    closed_deals INT NOT NULL DEFAULT 0,
    new_listings INT NOT NULL DEFAULT 0,
    new_customers INT NOT NULL DEFAULT 0,
    completion_rate DECIMAL(10,2) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0
);

INSERT INTO employee_kpi_stat (employee_id, employee_name, stat_month, closed_deals, new_listings, new_customers, completion_rate)
VALUES (1001, 'Zhang San', '2026-04', 4, 12, 25, 1.08),
       (1002, 'Li Si', '2026-04', 3, 9, 19, 0.95);

USE bk_contract;
CREATE TABLE IF NOT EXISTS contract_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    contract_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    expiry_date VARCHAR(16) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0
);

INSERT INTO contract_record (contract_type, status, expiry_date)
VALUES ('SALE', 'PENDING_SIGN', '2026-06-30');

USE bk_settlement;
CREATE TABLE IF NOT EXISTS settlement_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    employee_id BIGINT NOT NULL,
    stat_month VARCHAR(16) NOT NULL,
    commission_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
    payout_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0
);

INSERT INTO settlement_record (employee_id, stat_month, commission_amount, payout_status)
VALUES (1001, '2026-04', 52000.00, 'PENDING');
