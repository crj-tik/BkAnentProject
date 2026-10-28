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
