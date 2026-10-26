-- Additive execution-binding facts only. bk_auth is created by the existing bootstrap/migrations.
-- Apply on fresh/upgraded bk_auth and repeat safely; no user/role seeds, data rewrites or credentials.
USE bk_auth;

-- One verified ingress can name exactly one execution tuple across Auth instances.
-- Absolute deadlines are UTC epoch seconds from the original ingress context, not retry/proof times.
-- Do not delete a binding to allow a still-valid ingress to bind again; these facts are not login tickets.
CREATE TABLE IF NOT EXISTS auth_authorization_execution_binding (
    ingress_request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    tuple_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id BIGINT NOT NULL,
    caller VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    session_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    policy_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operations_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    execution_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at BIGINT NOT NULL,
    hard_deadline BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_auth_exec_ingress CHECK (REGEXP_LIKE(ingress_request_hash, '^[a-f0-9]{64}$')),
    CONSTRAINT chk_auth_exec_tuple CHECK (REGEXP_LIKE(tuple_hash, '^[a-f0-9]{64}$')),
    CONSTRAINT chk_auth_exec_policy CHECK (REGEXP_LIKE(policy_digest, '^[a-f0-9]{64}$')),
    CONSTRAINT chk_auth_exec_operations CHECK (REGEXP_LIKE(operations_hash, '^[a-f0-9]{64}$')),
    CONSTRAINT chk_auth_exec_execution CHECK (REGEXP_LIKE(execution_hash, '^[a-f0-9]{64}$')),
    CONSTRAINT chk_auth_exec_actor CHECK (actor_id > 0),
    CONSTRAINT chk_auth_exec_caller CHECK (REGEXP_LIKE(caller, '^[a-z][a-z0-9-]{0,95}$')),
    CONSTRAINT chk_auth_exec_run CHECK (CHAR_LENGTH(TRIM(run_id)) > 0),
    CONSTRAINT chk_auth_exec_session CHECK (CHAR_LENGTH(TRIM(session_id)) > 0),
    CONSTRAINT chk_auth_exec_deadlines CHECK (expires_at > 0 AND hard_deadline >= expires_at)
) ENGINE=InnoDB;
