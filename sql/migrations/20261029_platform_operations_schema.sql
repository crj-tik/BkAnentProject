-- Platform operations schema: migrated business, review, knowledge and A2A definitions.


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


CREATE TABLE IF NOT EXISTS store_review_matter (
    review_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    store_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    dataset_id VARCHAR(128) NOT NULL,
    data_mode VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL,
    matter_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (review_id),
    KEY idx_store_review_matter_store_created (store_id, created_at, review_id),
    KEY idx_store_review_matter_dataset (dataset_id, data_mode)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS store_review_evidence (
    evidence_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    review_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_system VARCHAR(96) NOT NULL,
    source_id VARCHAR(192) NOT NULL,
    revision VARCHAR(128) NOT NULL,
    observed_at DATETIME(6) NOT NULL,
    event_time DATETIME(6) NULL,
    data_mode VARCHAR(16) NOT NULL,
    dataset_id VARCHAR(128) NOT NULL,
    source_status VARCHAR(32) NOT NULL,
    evidence_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (evidence_id),
    KEY idx_store_review_evidence_review (review_id),
    KEY idx_store_review_evidence_source_revision (source_system, source_id, revision),
    CONSTRAINT fk_store_review_evidence_matter FOREIGN KEY (review_id)
        REFERENCES store_review_matter (review_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS store_review_diagnosis_revision (
    review_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    diagnosis_revision INT NOT NULL,
    diagnosis_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (review_id, diagnosis_revision),
    CONSTRAINT fk_store_review_diagnosis_matter FOREIGN KEY (review_id)
        REFERENCES store_review_matter (review_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS store_review_plan_revision (
    review_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    plan_version INT NOT NULL,
    diagnosis_revision INT NOT NULL,
    owner_actor_id BIGINT NOT NULL,
    due_at DATETIME(6) NOT NULL,
    parameter_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    plan_status VARCHAR(24) NOT NULL,
    plan_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (review_id, plan_version),
    KEY idx_store_review_plan_status (review_id, plan_status),
    CONSTRAINT fk_store_review_plan_matter FOREIGN KEY (review_id)
        REFERENCES store_review_matter (review_id),
    CONSTRAINT fk_store_review_plan_diagnosis FOREIGN KEY (review_id, diagnosis_revision)
        REFERENCES store_review_diagnosis_revision (review_id, diagnosis_revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS store_review_action (
    action_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    review_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    plan_version INT NOT NULL,
    action_version BIGINT NOT NULL DEFAULT 1,
    action_status VARCHAR(24) NOT NULL,
    evidence_level VARCHAR(24) NULL,
    feedback_note TEXT NULL,
    action_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (action_id),
    KEY idx_store_review_action_review (review_id, plan_version),
    KEY idx_store_review_action_status (action_status),
    CONSTRAINT fk_store_review_action_plan FOREIGN KEY (review_id, plan_version)
        REFERENCES store_review_plan_revision (review_id, plan_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS store_review_action_receipt (
    receipt_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    review_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    receipt_type VARCHAR(32) NOT NULL,
    receipt_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (receipt_id),
    UNIQUE KEY uk_store_review_action_receipt_request (review_id, request_id, receipt_type),
    CONSTRAINT fk_store_review_receipt_matter FOREIGN KEY (review_id)
        REFERENCES store_review_matter (review_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS store_review_idempotency (
    scope_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    request_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    receipt_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (scope_key, request_id),
    KEY idx_store_review_idempotency_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS store_review_observation (
    observation_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    review_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    data_mode VARCHAR(16) NOT NULL,
    observed_at DATETIME(6) NOT NULL,
    observation_json LONGTEXT NOT NULL,
    PRIMARY KEY (observation_id),
    KEY idx_store_review_observation_review_time (review_id, observed_at),
    CONSTRAINT fk_store_review_observation_matter FOREIGN KEY (review_id)
        REFERENCES store_review_matter (review_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS knowledge_case (
    knowledge_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_actor_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    store_id BIGINT NULL,
    title VARCHAR(200) NOT NULL,
    business_problem VARCHAR(1000) NOT NULL,
    scene VARCHAR(120) NOT NULL,
    tags_json JSON NOT NULL,
    lifecycle_state VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    current_revision INT NOT NULL DEFAULT 1,
    published_revision INT NULL,
    data_mode VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UNKNOWN',
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (knowledge_id),
    KEY idx_knowledge_case_owner_state (owner_actor_id, lifecycle_state, deleted, updated_at),
    KEY idx_knowledge_case_store_state (store_id, lifecycle_state, deleted, updated_at),
    KEY idx_knowledge_case_scene (scene, lifecycle_state, deleted),
    CONSTRAINT chk_knowledge_case_scope CHECK (scope_type IN ('OWN','STORE')),
    CONSTRAINT chk_knowledge_case_state CHECK (lifecycle_state IN ('DRAFT','REVIEW_PENDING','PUBLISHED','WITHDRAWN')),
    CONSTRAINT chk_knowledge_case_data_mode CHECK (data_mode IN ('REAL','SIMULATED','MIXED','UNKNOWN')),
    CONSTRAINT chk_knowledge_case_scope_identity CHECK
        ((scope_type='OWN' AND store_id IS NULL) OR (scope_type='STORE' AND store_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS knowledge_revision (
    revision_id BIGINT NOT NULL AUTO_INCREMENT,
    knowledge_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision_no INT NOT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    summary VARCHAR(512) NOT NULL,
    body_json JSON NOT NULL,
    data_mode VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_level VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    input_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    rules_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by_actor_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (revision_id),
    UNIQUE KEY uk_knowledge_revision_no (knowledge_id, revision_no),
    CONSTRAINT fk_knowledge_revision_case FOREIGN KEY (knowledge_id) REFERENCES knowledge_case (knowledge_id),
    CONSTRAINT chk_knowledge_revision_mode CHECK (data_mode IN ('REAL','SIMULATED','MIXED','UNKNOWN')),
    CONSTRAINT chk_knowledge_revision_evidence CHECK (evidence_level IN ('SELF_REPORTED','VERIFIED','UNVERIFIED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS knowledge_source_ref (
    source_ref_id BIGINT NOT NULL AUTO_INCREMENT,
    knowledge_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision_no INT NOT NULL,
    asset_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    snapshot_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_revision INT NOT NULL,
    sanitization_revision INT NOT NULL,
    publication_revision INT NOT NULL,
    data_mode VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UNKNOWN',
    quote_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    quote_start INT NULL,
    quote_end INT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (source_ref_id),
    UNIQUE KEY uk_knowledge_source_asset_revision (knowledge_id, revision_no, asset_id),
    KEY idx_knowledge_source_asset (asset_id, content_hash),
    CONSTRAINT fk_knowledge_source_revision FOREIGN KEY (knowledge_id, revision_no)
        REFERENCES knowledge_revision (knowledge_id, revision_no),
    CONSTRAINT chk_knowledge_source_locator CHECK
        ((quote_hash IS NULL AND quote_start IS NULL AND quote_end IS NULL)
         OR (quote_hash IS NOT NULL AND quote_start >= 0 AND quote_end > quote_start)),
    CONSTRAINT chk_knowledge_source_mode CHECK (data_mode IN ('REAL','SIMULATED','MIXED','UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS knowledge_review_decision (
    decision_id BIGINT NOT NULL AUTO_INCREMENT,
    knowledge_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision_no INT NOT NULL,
    reviewer_actor_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    decision VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(2000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (decision_id),
    KEY idx_knowledge_review_target (knowledge_id, revision_no, decision_id),
    CONSTRAINT fk_knowledge_review_revision FOREIGN KEY (knowledge_id, revision_no)
        REFERENCES knowledge_revision (knowledge_id, revision_no),
    CONSTRAINT chk_knowledge_review_decision CHECK (decision IN ('APPROVE','REJECT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS knowledge_operation_receipt (
    receipt_id BIGINT NOT NULL AUTO_INCREMENT,
    request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    response_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (receipt_id),
    UNIQUE KEY uk_knowledge_receipt_request_operation (request_id, operation),
    KEY idx_knowledge_receipt_target (target_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS knowledge_generation_task (
    task_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    knowledge_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    input_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    rules_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_revision_no INT NULL,
    result_json JSON NULL,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_by_actor_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (task_id),
    UNIQUE KEY uk_knowledge_generation_request (request_id),
    KEY idx_knowledge_generation_state (status, updated_at),
    CONSTRAINT fk_knowledge_generation_case FOREIGN KEY (knowledge_id) REFERENCES knowledge_case (knowledge_id),
    CONSTRAINT chk_knowledge_generation_state CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE OR REPLACE ALGORITHM=MERGE DEFINER='knowledge_source_view_owner'@'%'
SQL SECURITY DEFINER VIEW knowledge_interview_public_source_v1 AS
SELECT a.id AS asset_id,
       a.case_id,
       a.session_id,
       a.source,
       a.title,
       a.snapshot_hash,
       a.archive_grade,
       a.content_hash,
       a.content_revision,
       a.sanitization_revision,
       a.publication_revision,
       a.content,
       CASE WHEN a.session_id IS NULL THEN 'UPLOAD' ELSE 'ARCHIVED_SESSION' END AS source_relation
FROM bk_interview.interview_asset a
JOIN bk_interview.interview_case c ON c.id=a.case_id AND c.deleted=0
LEFT JOIN bk_interview.interview_session s
    ON s.id=a.session_id AND s.deleted=0
WHERE a.visibility='PUBLISHED'
  AND a.archive_grade IN ('L2','L3')
  AND a.sanitization_revision>=1
  AND a.deleted=0
  AND a.content_hash IS NOT NULL
  AND SHA2(a.content,256)=a.content_hash
  AND ((a.session_id IS NULL AND a.source='UPLOAD')
       OR (s.id IS NOT NULL AND s.case_id=a.case_id AND s.status='ARCHIVED'));


-- Apply once to each service database that hosts an A2A provider.
-- These rows bind task IDs to authenticated ownership and reject cross-instance replay;
-- they are not bearer credentials and never create a local execution lease by themselves.
CREATE TABLE IF NOT EXISTS a2a_request_nonce (
    nonce CHAR(43) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (nonce),
    KEY idx_a2a_request_nonce_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS a2a_task_owner (
    task_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    actor_id BIGINT NOT NULL,
    caller_service VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    context_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    run_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    session_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (task_id),
    KEY idx_a2a_task_owner_actor (actor_id, caller_service)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS a2a_task_execution_reservation (
    task_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    actor_id BIGINT NOT NULL,
    request_nonce CHAR(43) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reserved_until BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (task_id),
    UNIQUE KEY uk_a2a_task_reservation_nonce (request_nonce),
    KEY idx_a2a_task_reservation_expiry (reserved_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'platform_operations_app'@'%' ACCOUNT LOCK;
CREATE USER IF NOT EXISTS 'platform_operations_source_ro'@'%' ACCOUNT LOCK;
GRANT SELECT ON bk_platform_operations.employee_kpi_stat TO 'platform_operations_app'@'%';
GRANT SELECT ON bk_platform_operations.employee_daily_workload TO 'platform_operations_app'@'%';
GRANT SELECT ON bk_platform_operations.listing_turnover_stat TO 'platform_operations_app'@'%';
GRANT SELECT ON bk_platform_operations.store_dashboard_snapshot TO 'platform_operations_app'@'%';
GRANT SELECT, INSERT, UPDATE ON bk_platform_operations.store_review_matter TO 'platform_operations_app'@'%';
GRANT SELECT, INSERT ON bk_platform_operations.store_review_evidence TO 'platform_operations_app'@'%';
GRANT SELECT, INSERT ON bk_platform_operations.store_review_diagnosis_revision TO 'platform_operations_app'@'%';
GRANT SELECT, INSERT, UPDATE ON bk_platform_operations.store_review_plan_revision TO 'platform_operations_app'@'%';
GRANT SELECT, INSERT, UPDATE ON bk_platform_operations.store_review_action TO 'platform_operations_app'@'%';
GRANT SELECT, INSERT ON bk_platform_operations.store_review_action_receipt TO 'platform_operations_app'@'%';
GRANT SELECT, INSERT, UPDATE ON bk_platform_operations.store_review_idempotency TO 'platform_operations_app'@'%';
GRANT SELECT, INSERT ON bk_platform_operations.store_review_observation TO 'platform_operations_app'@'%';
GRANT SELECT, INSERT, UPDATE ON bk_platform_operations.knowledge_case TO 'platform_operations_app'@'%';
GRANT SELECT, INSERT ON bk_platform_operations.knowledge_revision TO 'platform_operations_app'@'%';
GRANT SELECT, INSERT ON bk_platform_operations.knowledge_source_ref TO 'platform_operations_app'@'%';
GRANT SELECT, INSERT ON bk_platform_operations.knowledge_review_decision TO 'platform_operations_app'@'%';
GRANT SELECT, INSERT, UPDATE ON bk_platform_operations.knowledge_operation_receipt TO 'platform_operations_app'@'%';
GRANT SELECT, INSERT, UPDATE ON bk_platform_operations.knowledge_generation_task TO 'platform_operations_app'@'%';
GRANT SELECT, INSERT, DELETE ON bk_platform_operations.a2a_request_nonce TO 'platform_operations_app'@'%';
GRANT SELECT, INSERT ON bk_platform_operations.a2a_task_owner TO 'platform_operations_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON bk_platform_operations.a2a_task_execution_reservation TO 'platform_operations_app'@'%';
GRANT SELECT ON bk_platform_operations.knowledge_interview_public_source_v1 TO 'platform_operations_source_ro'@'%';
