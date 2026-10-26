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
