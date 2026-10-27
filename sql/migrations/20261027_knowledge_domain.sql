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
    UNIQUE KEY uk_knowledge_revision_hash (knowledge_id, content_hash),
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

CREATE USER IF NOT EXISTS 'knowledge_app'@'%' ACCOUNT LOCK;
CREATE USER IF NOT EXISTS 'knowledge_source_ro'@'%' ACCOUNT LOCK;
GRANT SELECT, INSERT, UPDATE ON bk_knowledge.knowledge_case TO 'knowledge_app'@'%';
GRANT SELECT, INSERT ON bk_knowledge.knowledge_revision TO 'knowledge_app'@'%';
GRANT SELECT, INSERT ON bk_knowledge.knowledge_source_ref TO 'knowledge_app'@'%';
GRANT SELECT, INSERT ON bk_knowledge.knowledge_review_decision TO 'knowledge_app'@'%';
GRANT SELECT, INSERT ON bk_knowledge.knowledge_operation_receipt TO 'knowledge_app'@'%';
GRANT SELECT, INSERT, UPDATE ON bk_knowledge.knowledge_generation_task TO 'knowledge_app'@'%';
GRANT SELECT ON bk_knowledge.knowledge_interview_public_source_v1 TO 'knowledge_source_ro'@'%';
