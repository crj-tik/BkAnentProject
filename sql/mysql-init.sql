CREATE DATABASE IF NOT EXISTS bk_auth DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS bk_listing DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS bk_customer DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS bk_notification DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS bk_marketing DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS bk_promotion DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS bk_business DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS bk_agent DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS bk_contract DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS bk_settlement DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS nacos DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;


USE bk_auth;

CREATE TABLE IF NOT EXISTS user_account (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(128) NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    role_code VARCHAR(32) NOT NULL,
    tenant_code VARCHAR(64) NOT NULL,
    account_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_account_username (username),
    KEY idx_user_account_role_code (role_code),
    KEY idx_user_account_tenant_code (tenant_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO user_account (username, password_hash, display_name, role_code, tenant_code, account_status)
SELECT 'broker01', 'plain-demo-password', 'Broker Demo', 'BROKER', 'store-shanghai-001', 'ACTIVE'
WHERE NOT EXISTS (
    SELECT 1 FROM user_account WHERE username = 'broker01'
);

USE bk_listing;

CREATE TABLE IF NOT EXISTS listing_info (
    id BIGINT NOT NULL AUTO_INCREMENT,
    broker_id BIGINT NULL,
    title VARCHAR(128) NOT NULL,
    address VARCHAR(255) NOT NULL,
    layout VARCHAR(32) NOT NULL,
    area DECIMAL(10, 2) NOT NULL,
    total_price DECIMAL(12, 2) NOT NULL,
    status VARCHAR(32) NOT NULL,
    floor_level VARCHAR(32) NULL,
    decoration VARCHAR(32) NULL,
    school_zone VARCHAR(64) NULL,
    traffic VARCHAR(64) NULL,
    owner_name VARCHAR(64) NULL,
    certificate_no VARCHAR(64) NULL,
    property_certificate_url VARCHAR(255) NULL,
    contract_url VARCHAR(255) NULL,
    image_urls TEXT NULL,
    floor_plan_urls TEXT NULL,
    video_urls TEXT NULL,
    ocr_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    verification_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    verification_source VARCHAR(64) NULL,
    verification_remark VARCHAR(255) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_listing_info_broker_id (broker_id),
    KEY idx_listing_info_status (status),
    KEY idx_listing_info_verification_status (verification_status),
    KEY idx_listing_info_certificate_no (certificate_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

USE bk_customer;

CREATE TABLE IF NOT EXISTS customer_profile (
    id BIGINT NOT NULL AUTO_INCREMENT,
    profile_type VARCHAR(32) NOT NULL,
    name VARCHAR(64) NOT NULL,
    mobile VARCHAR(32) NOT NULL,
    wechat_no VARCHAR(64) NULL,
    gender VARCHAR(16) NULL,
    intention VARCHAR(64) NULL,
    preferred_area VARCHAR(128) NULL,
    preferred_layout VARCHAR(64) NULL,
    budget_min DECIMAL(12, 2) NULL,
    budget_max DECIMAL(12, 2) NULL,
    preferred_area_min DECIMAL(10, 2) NULL,
    preferred_area_max DECIMAL(10, 2) NULL,
    broker_id BIGINT NULL,
    source_channel VARCHAR(64) NULL,
    remark VARCHAR(255) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_customer_profile_profile_type (profile_type),
    KEY idx_customer_profile_mobile (mobile),
    KEY idx_customer_profile_broker_id (broker_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS customer_follow_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    customer_id BIGINT NOT NULL,
    broker_id BIGINT NOT NULL,
    follow_type VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    result_tag VARCHAR(64) NULL,
    next_follow_time DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_customer_follow_record_customer_id (customer_id),
    KEY idx_customer_follow_record_broker_id (broker_id),
    KEY idx_customer_follow_record_next_follow_time (next_follow_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS owner_entrust_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    customer_id BIGINT NOT NULL,
    listing_id BIGINT NOT NULL,
    contract_no VARCHAR(64) NOT NULL,
    entrust_start_date DATE NULL,
    entrust_end_date DATE NULL,
    reminder_days INT NULL,
    status VARCHAR(32) NOT NULL,
    remark VARCHAR(255) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_owner_entrust_record_customer_id (customer_id),
    KEY idx_owner_entrust_record_listing_id (listing_id),
    KEY idx_owner_entrust_record_status (status),
    KEY idx_owner_entrust_record_entrust_end_date (entrust_end_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS customer_favorite_listing (
    id BIGINT NOT NULL AUTO_INCREMENT,
    customer_id BIGINT NOT NULL,
    listing_id BIGINT NOT NULL,
    favorite_source VARCHAR(64) NULL,
    remark VARCHAR(255) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_customer_favorite_listing_customer_id (customer_id),
    KEY idx_customer_favorite_listing_listing_id (listing_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

USE bk_notification;

CREATE TABLE IF NOT EXISTS notification_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    recipient_user_id BIGINT NOT NULL,
    channel VARCHAR(32) NOT NULL,
    scene_code VARCHAR(64) NOT NULL,
    title VARCHAR(128) NOT NULL,
    content TEXT NOT NULL,
    receiver_address VARCHAR(128) NULL,
    external_webhook VARCHAR(255) NULL,
    send_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    read_status VARCHAR(32) NOT NULL DEFAULT 'UNREAD',
    read_time DATETIME NULL,
    send_time DATETIME NULL,
    external_message_id VARCHAR(128) NULL,
    error_message VARCHAR(255) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_notification_record_recipient_user_id (recipient_user_id),
    KEY idx_notification_record_channel (channel),
    KEY idx_notification_record_send_status (send_status),
    KEY idx_notification_record_read_status (read_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

USE bk_marketing;

CREATE TABLE IF NOT EXISTS marketing_content (
    id BIGINT NOT NULL AUTO_INCREMENT,
    listing_id BIGINT NOT NULL,
    platform VARCHAR(32) NOT NULL,
    title VARCHAR(128) NOT NULL,
    content_type VARCHAR(32) NOT NULL,
    copywriting TEXT NULL,
    asset_urls TEXT NULL,
    cover_image_url VARCHAR(255) NULL,
    video_url VARCHAR(255) NULL,
    version_no INT NOT NULL DEFAULT 1,
    parent_content_id BIGINT NULL,
    platform_variant VARCHAR(64) NULL,
    tags VARCHAR(255) NULL,
    audit_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    publish_message VARCHAR(255) NULL,
    external_publish_id VARCHAR(128) NULL,
    publish_time DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_marketing_content_listing_id (listing_id),
    KEY idx_marketing_content_platform (platform),
    KEY idx_marketing_content_status (status),
    KEY idx_marketing_content_audit_status (audit_status),
    KEY idx_marketing_content_parent_content_id (parent_content_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

USE bk_promotion;

CREATE TABLE IF NOT EXISTS promotion_publish_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    content_id BIGINT NOT NULL,
    listing_id BIGINT NOT NULL,
    platform VARCHAR(32) NOT NULL,
    channel_account VARCHAR(128) NULL,
    publish_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    external_publish_id VARCHAR(128) NULL,
    publish_time DATETIME NULL,
    publish_message VARCHAR(255) NULL,
    cost_amount DECIMAL(12, 2) NULL,
    operator_name VARCHAR(64) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_promotion_publish_record_content_id (content_id),
    KEY idx_promotion_publish_record_listing_id (listing_id),
    KEY idx_promotion_publish_record_platform (platform),
    KEY idx_promotion_publish_record_publish_status (publish_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS promotion_effect_stat (
    id BIGINT NOT NULL AUTO_INCREMENT,
    publish_record_id BIGINT NOT NULL,
    content_id BIGINT NOT NULL,
    listing_id BIGINT NOT NULL,
    platform VARCHAR(32) NOT NULL,
    exposure_count INT NOT NULL DEFAULT 0,
    click_count INT NOT NULL DEFAULT 0,
    private_message_count INT NOT NULL DEFAULT 0,
    lead_count INT NOT NULL DEFAULT 0,
    ctr_value DECIMAL(10, 4) NULL,
    conversion_rate DECIMAL(10, 4) NULL,
    roi_value DECIMAL(10, 4) NULL,
    stat_date VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_promotion_effect_stat_publish_record_id (publish_record_id),
    KEY idx_promotion_effect_stat_content_id (content_id),
    KEY idx_promotion_effect_stat_listing_id (listing_id),
    KEY idx_promotion_effect_stat_platform (platform),
    KEY idx_promotion_effect_stat_stat_date (stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS brand_asset (
    id BIGINT NOT NULL AUTO_INCREMENT,
    asset_type VARCHAR(32) NOT NULL,
    asset_name VARCHAR(128) NOT NULL,
    asset_url VARCHAR(255) NOT NULL,
    platform_scope VARCHAR(64) NULL,
    tag_names VARCHAR(255) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    remark VARCHAR(255) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_brand_asset_asset_type (asset_type),
    KEY idx_brand_asset_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

USE bk_business;

CREATE TABLE IF NOT EXISTS employee_kpi_stat (
    id BIGINT NOT NULL AUTO_INCREMENT,
    employee_id BIGINT NOT NULL,
    employee_name VARCHAR(64) NOT NULL,
    store_name VARCHAR(64) NOT NULL,
    region_name VARCHAR(64) NULL,
    stat_month VARCHAR(16) NOT NULL,
    sale_deals INT NOT NULL DEFAULT 0,
    rental_deals INT NOT NULL DEFAULT 0,
    closed_deals INT NOT NULL DEFAULT 0,
    viewing_count INT NOT NULL DEFAULT 0,
    new_listings INT NOT NULL DEFAULT 0,
    new_customers INT NOT NULL DEFAULT 0,
    private_message_count INT NOT NULL DEFAULT 0,
    performance_amount DECIMAL(14, 2) NULL,
    completion_rate DECIMAL(10, 4) NULL,
    conversion_rate DECIMAL(10, 4) NULL,
    satisfaction_score DECIMAL(10, 4) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_employee_kpi_stat_employee_id (employee_id),
    KEY idx_employee_kpi_stat_store_name (store_name),
    KEY idx_employee_kpi_stat_region_name (region_name),
    KEY idx_employee_kpi_stat_stat_month (stat_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS employee_daily_workload (
    id BIGINT NOT NULL AUTO_INCREMENT,
    employee_id BIGINT NOT NULL,
    employee_name VARCHAR(64) NOT NULL,
    store_name VARCHAR(64) NOT NULL,
    region_name VARCHAR(64) NULL,
    stat_date VARCHAR(16) NOT NULL,
    viewing_count INT NOT NULL DEFAULT 0,
    new_listings INT NOT NULL DEFAULT 0,
    new_customers INT NOT NULL DEFAULT 0,
    follow_up_count INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_employee_daily_workload_employee_id (employee_id),
    KEY idx_employee_daily_workload_store_name (store_name),
    KEY idx_employee_daily_workload_region_name (region_name),
    KEY idx_employee_daily_workload_stat_date (stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS listing_turnover_stat (
    id BIGINT NOT NULL AUTO_INCREMENT,
    listing_id BIGINT NOT NULL,
    listing_title VARCHAR(128) NOT NULL,
    store_name VARCHAR(64) NOT NULL,
    region_name VARCHAR(64) NULL,
    stat_month VARCHAR(16) NOT NULL,
    listing_to_viewing_days INT NULL,
    viewing_to_deal_days INT NULL,
    total_turnover_days INT NULL,
    turnover_status VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_listing_turnover_stat_listing_id (listing_id),
    KEY idx_listing_turnover_stat_store_name (store_name),
    KEY idx_listing_turnover_stat_region_name (region_name),
    KEY idx_listing_turnover_stat_stat_month (stat_month),
    KEY idx_listing_turnover_stat_turnover_status (turnover_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS store_dashboard_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    store_name VARCHAR(64) NOT NULL,
    region_name VARCHAR(64) NULL,
    stat_date VARCHAR(16) NOT NULL,
    active_listing_count INT NOT NULL DEFAULT 0,
    today_viewing_count INT NOT NULL DEFAULT 0,
    today_new_customer_count INT NOT NULL DEFAULT 0,
    today_deal_count INT NOT NULL DEFAULT 0,
    today_performance_amount DECIMAL(14, 2) NULL,
    satisfaction_score DECIMAL(10, 4) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_store_dashboard_snapshot_store_name (store_name),
    KEY idx_store_dashboard_snapshot_region_name (region_name),
    KEY idx_store_dashboard_snapshot_stat_date (stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

USE bk_contract;

CREATE TABLE IF NOT EXISTS contract_template (
    id BIGINT NOT NULL AUTO_INCREMENT,
    template_code VARCHAR(64) NOT NULL,
    template_name VARCHAR(128) NOT NULL,
    contract_type VARCHAR(32) NOT NULL,
    version_no INT NOT NULL DEFAULT 1,
    template_content LONGTEXT NULL,
    template_file_url VARCHAR(255) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    remark VARCHAR(255) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_contract_template_template_code (template_code),
    KEY idx_contract_template_contract_type (contract_type),
    KEY idx_contract_template_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS contract_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    template_id BIGINT NULL,
    contract_no VARCHAR(64) NOT NULL,
    title VARCHAR(128) NOT NULL,
    contract_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    expiry_date VARCHAR(32) NULL,
    broker_id BIGINT NULL,
    listing_id BIGINT NULL,
    customer_name VARCHAR(64) NULL,
    party_a_name VARCHAR(64) NULL,
    party_b_name VARCHAR(64) NULL,
    deal_amount DECIMAL(14, 2) NULL,
    signed_document_url VARCHAR(255) NULL,
    external_seal_no VARCHAR(128) NULL,
    sign_start_time VARCHAR(32) NULL,
    both_signed_time VARCHAR(32) NULL,
    archived_time VARCHAR(32) NULL,
    dispute_time VARCHAR(32) NULL,
    archive_status VARCHAR(32) NULL,
    seal_status VARCHAR(32) NULL,
    seal_provider VARCHAR(64) NULL,
    seal_time VARCHAR(32) NULL,
    ocr_summary TEXT NULL,
    remark VARCHAR(255) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_contract_record_contract_no (contract_no),
    KEY idx_contract_record_template_id (template_id),
    KEY idx_contract_record_contract_type (contract_type),
    KEY idx_contract_record_status (status),
    KEY idx_contract_record_listing_id (listing_id),
    KEY idx_contract_record_broker_id (broker_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS contract_attachment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    contract_id BIGINT NOT NULL,
    attachment_type VARCHAR(32) NOT NULL,
    file_name VARCHAR(128) NOT NULL,
    file_url VARCHAR(255) NOT NULL,
    ocr_status VARCHAR(32) NULL,
    ocr_text LONGTEXT NULL,
    ocr_structured_data LONGTEXT NULL,
    ocr_provider VARCHAR(64) NULL,
    ocr_time VARCHAR(32) NULL,
    remark VARCHAR(255) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_contract_attachment_contract_id (contract_id),
    KEY idx_contract_attachment_attachment_type (attachment_type),
    KEY idx_contract_attachment_ocr_status (ocr_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO contract_template (
    template_code, template_name, contract_type, version_no, template_content, template_file_url, status, remark
)
SELECT
    'SALE_DEFAULT_V1',
    'Sale Contract Default Template',
    'SALE',
    1,
    'Default sale contract template content.',
    'https://example.local/contracts/templates/sale-default-v1.docx',
    'ACTIVE',
    'Initialized by mysql-init.sql'
WHERE NOT EXISTS (
    SELECT 1 FROM contract_template WHERE template_code = 'SALE_DEFAULT_V1'
);

USE bk_settlement;

CREATE TABLE IF NOT EXISTS settlement_rule (
    id BIGINT NOT NULL AUTO_INCREMENT,
    rule_code VARCHAR(64) NOT NULL,
    rule_name VARCHAR(128) NOT NULL,
    contract_type VARCHAR(32) NOT NULL,
    min_deal_amount DECIMAL(14, 2) NULL,
    max_deal_amount DECIMAL(14, 2) NULL,
    commission_rate DECIMAL(10, 4) NULL,
    store_split_ratio DECIMAL(10, 4) NULL,
    team_split_ratio DECIMAL(10, 4) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    remark VARCHAR(255) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_settlement_rule_rule_code (rule_code),
    KEY idx_settlement_rule_contract_type (contract_type),
    KEY idx_settlement_rule_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS settlement_rule_tier (
    id BIGINT NOT NULL AUTO_INCREMENT,
    rule_id BIGINT NOT NULL,
    tier_level INT NOT NULL,
    min_deal_amount DECIMAL(14, 2) NULL,
    max_deal_amount DECIMAL(14, 2) NULL,
    commission_rate DECIMAL(10, 4) NULL,
    store_split_ratio DECIMAL(10, 4) NULL,
    team_split_ratio DECIMAL(10, 4) NULL,
    remark VARCHAR(255) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_settlement_rule_tier_rule_level (rule_id, tier_level),
    KEY idx_settlement_rule_tier_rule_id (rule_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS settlement_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    employee_id BIGINT NOT NULL,
    employee_name VARCHAR(64) NOT NULL,
    team_name VARCHAR(64) NULL,
    store_name VARCHAR(64) NULL,
    contract_id BIGINT NULL,
    listing_id BIGINT NULL,
    stat_month VARCHAR(16) NOT NULL,
    deal_amount DECIMAL(14, 2) NULL,
    commission_rate DECIMAL(10, 4) NULL,
    commission_amount DECIMAL(14, 2) NULL,
    payout_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    payout_time VARCHAR(32) NULL,
    rule_code VARCHAR(64) NULL,
    remark VARCHAR(255) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_settlement_record_employee_id (employee_id),
    KEY idx_settlement_record_contract_id (contract_id),
    KEY idx_settlement_record_listing_id (listing_id),
    KEY idx_settlement_record_stat_month (stat_month),
    KEY idx_settlement_record_payout_status (payout_status),
    KEY idx_settlement_record_rule_code (rule_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS settlement_split_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    settlement_id BIGINT NOT NULL,
    split_scope VARCHAR(32) NOT NULL,
    split_target_name VARCHAR(128) NOT NULL,
    split_ratio DECIMAL(10, 4) NULL,
    split_amount DECIMAL(14, 2) NULL,
    remark VARCHAR(255) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_settlement_split_record_settlement_id (settlement_id),
    KEY idx_settlement_split_record_split_scope (split_scope)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS settlement_monthly_summary (
    id BIGINT NOT NULL AUTO_INCREMENT,
    summary_scope VARCHAR(32) NOT NULL,
    employee_id BIGINT NULL,
    employee_name VARCHAR(64) NULL,
    team_name VARCHAR(64) NULL,
    stat_month VARCHAR(16) NOT NULL,
    deal_count INT NOT NULL DEFAULT 0,
    total_deal_amount DECIMAL(14, 2) NULL,
    total_commission_amount DECIMAL(14, 2) NULL,
    payout_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    remark VARCHAR(255) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_settlement_monthly_summary_summary_scope (summary_scope),
    KEY idx_settlement_monthly_summary_employee_id (employee_id),
    KEY idx_settlement_monthly_summary_stat_month (stat_month),
    KEY idx_settlement_monthly_summary_payout_status (payout_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS settlement_payout_batch (
    id BIGINT NOT NULL AUTO_INCREMENT,
    batch_no VARCHAR(64) NOT NULL,
    stat_month VARCHAR(16) NOT NULL,
    batch_status VARCHAR(32) NOT NULL DEFAULT 'CREATED',
    total_records INT NOT NULL DEFAULT 0,
    total_amount DECIMAL(14, 2) NULL,
    submit_time VARCHAR(32) NULL,
    paid_time VARCHAR(32) NULL,
    remark VARCHAR(255) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_settlement_payout_batch_batch_no (batch_no),
    KEY idx_settlement_payout_batch_stat_month (stat_month),
    KEY idx_settlement_payout_batch_batch_status (batch_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS settlement_payment_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    settlement_id BIGINT NOT NULL,
    payee_employee_id BIGINT NOT NULL,
    payee_name VARCHAR(64) NOT NULL,
    payment_amount DECIMAL(14, 2) NOT NULL,
    payment_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    payment_time VARCHAR(32) NULL,
    bank_serial_no VARCHAR(128) NULL,
    remark VARCHAR(255) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_settlement_payment_record_batch_id (batch_id),
    KEY idx_settlement_payment_record_settlement_id (settlement_id),
    KEY idx_settlement_payment_record_payee_employee_id (payee_employee_id),
    KEY idx_settlement_payment_record_payment_status (payment_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO settlement_rule (
    rule_code, rule_name, contract_type, min_deal_amount, max_deal_amount, commission_rate,
    store_split_ratio, team_split_ratio, status, remark
)
SELECT
    'SALE_DEFAULT',
    'Sale Default Commission Rule',
    'SALE',
    0.00,
    99999999.99,
    0.0100,
    0.2000,
    0.1000,
    'ACTIVE',
    'Initialized by mysql-init.sql'
WHERE NOT EXISTS (
    SELECT 1 FROM settlement_rule WHERE rule_code = 'SALE_DEFAULT'
);

INSERT INTO settlement_rule (
    rule_code, rule_name, contract_type, min_deal_amount, max_deal_amount, commission_rate,
    store_split_ratio, team_split_ratio, status, remark
)
SELECT
    'LEASE_DEFAULT',
    'Lease Default Commission Rule',
    'LEASE',
    0.00,
    99999999.99,
    0.0080,
    0.1000,
    0.0500,
    'ACTIVE',
    'Initialized by mysql-init.sql'
WHERE NOT EXISTS (
    SELECT 1 FROM settlement_rule WHERE rule_code = 'LEASE_DEFAULT'
);

INSERT INTO settlement_rule_tier (
    rule_id, tier_level, min_deal_amount, max_deal_amount, commission_rate, store_split_ratio, team_split_ratio, remark
)
SELECT
    r.id, 1, 0.00, 99999999.99, 0.0100, 0.2000, 0.1000, 'Default single tier'
FROM settlement_rule r
WHERE r.rule_code = 'SALE_DEFAULT'
  AND NOT EXISTS (
      SELECT 1 FROM settlement_rule_tier t WHERE t.rule_id = r.id AND t.tier_level = 1
  );

USE bk_agent;

CREATE TABLE IF NOT EXISTS agent_planner_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_no VARCHAR(64) NOT NULL,
    execution_mode VARCHAR(32) NOT NULL,
    user_message TEXT NOT NULL,
    final_answer LONGTEXT NULL,
    plan_summary VARCHAR(255) NULL,
    final_plan_json LONGTEXT NULL,
    tool_context LONGTEXT NULL,
    replan_count INT NOT NULL DEFAULT 0,
    completed TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_agent_planner_log_session_no (session_no),
    KEY idx_agent_planner_log_execution_mode (execution_mode),
    KEY idx_agent_planner_log_completed (completed)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_planner_step_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    planner_log_id BIGINT NOT NULL,
    session_no VARCHAR(64) NOT NULL,
    step_no INT NOT NULL,
    action VARCHAR(64) NOT NULL,
    success TINYINT NOT NULL DEFAULT 0,
    skipped TINYINT NOT NULL DEFAULT 0,
    request_content TEXT NULL,
    resolved_input LONGTEXT NULL,
    output_key VARCHAR(128) NULL,
    output_content LONGTEXT NULL,
    output_payload_json LONGTEXT NULL,
    error_message TEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_agent_planner_step_log_planner_log_id (planner_log_id),
    KEY idx_agent_planner_step_log_session_no (session_no),
    KEY idx_agent_planner_step_log_step_no (step_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
