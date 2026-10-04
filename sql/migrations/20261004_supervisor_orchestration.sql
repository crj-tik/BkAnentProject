-- Generic run/call facts only; no skill business-step progress.
CREATE TABLE IF NOT EXISTS agent_orchestration_run (
    run_id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    request_json LONGTEXT NOT NULL,
    lease_owner VARCHAR(128),
    lease_until_ms BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS agent_tool_invocation (
    run_id VARCHAR(128) NOT NULL,
    call_id VARCHAR(128) NOT NULL,
    capability_id VARCHAR(512) NOT NULL,
    arguments_hash CHAR(64) NOT NULL,
    arguments_json LONGTEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    result_json LONGTEXT,
    remote_association_json LONGTEXT,
    updated_at_ms BIGINT NOT NULL,
    PRIMARY KEY (run_id, call_id)
);
CREATE TABLE IF NOT EXISTS agent_skill_snapshot (
    run_id VARCHAR(128) NOT NULL,
    snapshot_id CHAR(64) NOT NULL,
    owner VARCHAR(128) NOT NULL,
    skill_name VARCHAR(128) NOT NULL,
    skill_version VARCHAR(128) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    snapshot_json LONGTEXT NOT NULL,
    PRIMARY KEY (run_id, snapshot_id)
);
