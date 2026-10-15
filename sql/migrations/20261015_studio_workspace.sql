USE bk_agent;

CREATE TABLE IF NOT EXISTS studio_work (
    work_id VARCHAR(64) PRIMARY KEY,
    owner_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    creation_request_id VARCHAR(128) NOT NULL,
    creation_hash CHAR(64) NOT NULL,
    title VARCHAR(200) NOT NULL,
    goal TEXT NOT NULL,
    context_json LONGTEXT NOT NULL,
    context_revision BIGINT NOT NULL DEFAULT 1,
    revision BIGINT NOT NULL DEFAULT 1,
    ui_json TEXT NOT NULL,
    ui_revision BIGINT NOT NULL DEFAULT 1,
    created_at_ms BIGINT NOT NULL,
    updated_at_ms BIGINT NOT NULL,
    UNIQUE KEY uk_studio_session (session_id),
    UNIQUE KEY uk_studio_creation (owner_id, creation_request_id),
    KEY idx_studio_owner_updated (owner_id, updated_at_ms, work_id)
);

CREATE TABLE IF NOT EXISTS studio_command (
    work_id VARCHAR(64) NOT NULL,
    owner_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    kind VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    receipt_json LONGTEXT NOT NULL,
    created_at_ms BIGINT NOT NULL,
    updated_at_ms BIGINT NOT NULL,
    PRIMARY KEY (work_id, request_id),
    KEY idx_studio_command_owner (owner_id, work_id)
);

CREATE TABLE IF NOT EXISTS studio_run (
    work_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    owner_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(128) NOT NULL,
    async_workflow_id VARCHAR(64) NOT NULL,
    context_revision BIGINT NOT NULL,
    artifacts_synced INT NOT NULL DEFAULT 0,
    input_json LONGTEXT NOT NULL,
    created_at_ms BIGINT NOT NULL,
    PRIMARY KEY (work_id, request_id),
    KEY idx_studio_run_work (work_id, created_at_ms, request_id)
);

CREATE TABLE IF NOT EXISTS studio_message (
    message_id VARCHAR(64) PRIMARY KEY,
    work_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128),
    role VARCHAR(16) NOT NULL,
    content LONGTEXT NOT NULL,
    created_at_ms BIGINT NOT NULL,
    UNIQUE KEY uk_studio_message (work_id, request_id, role),
    KEY idx_studio_message_work (work_id, created_at_ms, message_id)
);

CREATE TABLE IF NOT EXISTS studio_artifact_series (
    series_id VARCHAR(64) PRIMARY KEY,
    work_id VARCHAR(64) NOT NULL,
    owner_id VARCHAR(64) NOT NULL,
    source_artifact_id VARCHAR(128),
    artifact_type VARCHAR(64) NOT NULL,
    schema_version VARCHAR(16) NOT NULL,
    title VARCHAR(200) NOT NULL,
    current_version BIGINT NOT NULL,
    created_at_ms BIGINT NOT NULL,
    updated_at_ms BIGINT NOT NULL,
    UNIQUE KEY uk_studio_source (work_id, source_artifact_id),
    KEY idx_studio_series_work (work_id, created_at_ms, series_id)
);

CREATE TABLE IF NOT EXISTS studio_artifact_revision (
    revision_id VARCHAR(64) PRIMARY KEY,
    series_id VARCHAR(64) NOT NULL,
    version_no BIGINT NOT NULL,
    task_id VARCHAR(128),
    context_revision BIGINT NOT NULL,
    origin VARCHAR(32) NOT NULL,
    content_json LONGTEXT NOT NULL,
    sources_json LONGTEXT NOT NULL,
    dependencies_json LONGTEXT NOT NULL,
    note VARCHAR(500),
    derived_from_version BIGINT,
    created_at_ms BIGINT NOT NULL,
    UNIQUE KEY uk_studio_revision (series_id, version_no)
);
