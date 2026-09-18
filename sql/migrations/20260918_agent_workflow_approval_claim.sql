USE bk_agent;

CREATE TABLE IF NOT EXISTS agent_workflow_approval_claim (
    id BIGINT NOT NULL AUTO_INCREMENT,
    approval_id VARCHAR(64) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NULL,
    approval_version INT NULL,
    decision_status VARCHAR(32) NOT NULL,
    result_json LONGTEXT NULL,
    error_message TEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_workflow_approval_claim_approval_id (approval_id),
    KEY idx_agent_workflow_approval_claim_task_id (task_id),
    KEY idx_agent_workflow_approval_claim_status (decision_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
