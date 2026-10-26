-- Campaign lifecycle schema. Target database: bk_marketing. This file is not applied by this change.
CREATE TABLE IF NOT EXISTS marketing_campaign_content_revision (
    id BIGINT NOT NULL AUTO_INCREMENT,
    content_id BIGINT NOT NULL,
    revision_no INT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    snapshot_json LONGTEXT NOT NULL,
    data_mode VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_campaign_content_revision (content_id, revision_no),
    KEY idx_campaign_content_hash (content_hash)
);

CREATE TABLE IF NOT EXISTS marketing_campaign_brand_asset (
    id BIGINT NOT NULL AUTO_INCREMENT,
    asset_type VARCHAR(32) NOT NULL,
    asset_name VARCHAR(128) NOT NULL,
    asset_url VARCHAR(1024) NOT NULL,
    platform_scope VARCHAR(128) NULL,
    tag_names VARCHAR(1024) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    revision_no INT NOT NULL DEFAULT 1,
    legacy_source VARCHAR(64) NULL,
    legacy_id VARCHAR(128) NULL,
    remark VARCHAR(1024) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_campaign_brand_asset_legacy (legacy_source, legacy_id),
    KEY idx_campaign_brand_asset_type_status (asset_type, status)
);

CREATE TABLE IF NOT EXISTS marketing_campaign_account (
    id BIGINT NOT NULL AUTO_INCREMENT,
    platform VARCHAR(32) NOT NULL,
    external_account_ref VARCHAR(128) NOT NULL,
    permission_code VARCHAR(128) NOT NULL,
    revision_no INT NOT NULL DEFAULT 1,
    data_mode VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    active TINYINT NOT NULL DEFAULT 0,
    default_account TINYINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_campaign_account_platform_ref (platform, external_account_ref),
    KEY idx_campaign_account_active (platform, active)
);

CREATE TABLE IF NOT EXISTS marketing_campaign_plan (
    plan_id VARCHAR(36) NOT NULL,
    owner_user_id BIGINT NOT NULL,
    content_id BIGINT NOT NULL,
    content_revision INT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    asset_hash CHAR(64) NOT NULL,
    targets_hash CHAR(64) NOT NULL,
    rule_version VARCHAR(64) NOT NULL,
    snapshot_hash CHAR(64) NOT NULL,
    plan_version INT NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL,
    data_mode VARCHAR(16) NOT NULL,
    content_snapshot_json LONGTEXT NOT NULL,
    assets_snapshot_json LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (plan_id),
    KEY idx_campaign_plan_owner_created (owner_user_id, created_at),
    KEY idx_campaign_plan_content (content_id, content_revision)
);

CREATE TABLE IF NOT EXISTS marketing_campaign_plan_target (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id VARCHAR(36) NOT NULL,
    target_index INT NOT NULL,
    platform VARCHAR(32) NOT NULL,
    account_id BIGINT NOT NULL,
    account_revision INT NOT NULL,
    external_account_ref VARCHAR(128) NOT NULL,
    permission_code VARCHAR(128) NOT NULL,
    arguments_hash CHAR(64) NOT NULL,
    arguments_json TEXT NOT NULL,
    data_mode VARCHAR(16) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_campaign_plan_target_index (plan_id, target_index),
    KEY idx_campaign_plan_target_account (account_id)
);

CREATE TABLE IF NOT EXISTS marketing_campaign_publish_request (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id VARCHAR(36) NOT NULL,
    owner_user_id BIGINT NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    body_hash CHAR(64) NOT NULL,
    receipt_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_campaign_publish_plan (plan_id),
    UNIQUE KEY uk_campaign_publish_request (owner_user_id, request_id)
);

CREATE TABLE IF NOT EXISTS marketing_campaign_publish_receipt (
    receipt_id VARCHAR(36) NOT NULL,
    plan_id VARCHAR(36) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    data_mode VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (receipt_id),
    UNIQUE KEY uk_campaign_receipt_request (plan_id, request_id)
);

CREATE TABLE IF NOT EXISTS marketing_campaign_publish_attempt (
    attempt_id VARCHAR(36) NOT NULL,
    receipt_id VARCHAR(36) NOT NULL,
    plan_id VARCHAR(36) NOT NULL,
    target_index INT NOT NULL,
    platform VARCHAR(32) NOT NULL,
    account_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    data_mode VARCHAR(16) NOT NULL,
    external_publish_id VARCHAR(128) NULL,
    message VARCHAR(1024) NULL,
    attempt_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (attempt_id),
    UNIQUE KEY uk_campaign_attempt_target (receipt_id, target_index),
    KEY idx_campaign_attempt_plan_status (plan_id, status),
    KEY idx_campaign_attempt_external_id (external_publish_id)
);

CREATE TABLE IF NOT EXISTS marketing_campaign_outbox (
    event_id VARCHAR(36) NOT NULL,
    attempt_id VARCHAR(36) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    lease_token VARCHAR(36) NULL,
    lease_until TIMESTAMP(6) NULL,
    retry_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (event_id),
    UNIQUE KEY uk_campaign_outbox_attempt (attempt_id),
    KEY idx_campaign_outbox_due (state, next_attempt_at, lease_until)
);

CREATE TABLE IF NOT EXISTS marketing_campaign_event (
    event_id VARCHAR(36) NOT NULL,
    attempt_id VARCHAR(36) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    data_mode VARCHAR(16) NOT NULL,
    payload_json TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (event_id),
    KEY idx_campaign_event_attempt (attempt_id, created_at)
);

CREATE TABLE IF NOT EXISTS marketing_campaign_effect_observation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    attempt_id VARCHAR(36) NOT NULL,
    legacy_publish_record_id BIGINT NULL,
    content_id BIGINT NOT NULL,
    listing_id BIGINT NULL,
    platform VARCHAR(32) NOT NULL,
    source_system VARCHAR(64) NOT NULL,
    source_event_id VARCHAR(128) NOT NULL,
    source_version BIGINT NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    window_start TIMESTAMP(6) NOT NULL,
    window_end TIMESTAMP(6) NOT NULL,
    observed_at TIMESTAMP(6) NOT NULL,
    data_mode VARCHAR(16) NOT NULL,
    exposure_count BIGINT NULL,
    click_count BIGINT NULL,
    private_message_count BIGINT NULL,
    lead_count BIGINT NULL,
    attributable_revenue DECIMAL(18, 4) NULL,
    attributable_cost DECIMAL(18, 4) NULL,
    currency VARCHAR(3) NULL,
    roi_definition_version VARCHAR(64) NULL,
    unavailable_reason VARCHAR(128) NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_campaign_effect_source_event (source_system, source_event_id),
    KEY idx_campaign_effect_attempt_window (attempt_id, window_start, window_end),
    KEY idx_campaign_effect_listing (listing_id, platform, window_start)
);

CREATE TABLE IF NOT EXISTS marketing_campaign_effect_revision (
    id BIGINT NOT NULL AUTO_INCREMENT,
    observation_id BIGINT NOT NULL,
    source_version BIGINT NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    snapshot_json LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_campaign_effect_revision (observation_id, source_version),
    KEY idx_campaign_effect_revision_created (created_at)
);

CREATE TABLE IF NOT EXISTS marketing_campaign_legacy_map (
    id BIGINT NOT NULL AUTO_INCREMENT,
    source_system VARCHAR(64) NOT NULL,
    entity_type VARCHAR(32) NOT NULL,
    legacy_id VARCHAR(128) NOT NULL,
    target_table VARCHAR(64) NOT NULL,
    target_id VARCHAR(64) NOT NULL,
    row_hash CHAR(64) NULL,
    migrated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_campaign_legacy_source (source_system, entity_type, legacy_id),
    UNIQUE KEY uk_campaign_legacy_target (entity_type, target_table, target_id)
);
