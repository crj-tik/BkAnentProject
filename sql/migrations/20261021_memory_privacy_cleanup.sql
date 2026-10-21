-- Bodyless, idempotent privacy cleanup state for Memory v2 source events.
USE bk_memory;

CREATE TABLE IF NOT EXISTS memory_privacy_deletion (
    context_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_event_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    cleanup_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    last_error VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    requested_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (context_id, source_event_id),
    KEY idx_memory_privacy_state (cleanup_state, updated_at),
    CHECK (action_kind IN ('WITHDRAW', 'DELETE')),
    CHECK (cleanup_state IN ('PENDING', 'PROCESSING', 'RETRY', 'COMPLETE', 'FAILED')),
    CHECK (attempts >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS memory_privacy_cleanup_target (
    context_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_event_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    projection_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    cleanup_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    last_error VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (context_id, source_event_id, projection_id),
    KEY idx_memory_privacy_target_state (context_id, source_event_id, cleanup_state),
    CHECK (cleanup_state IN ('PENDING', 'COMPLETE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Preserve cleanup work accepted before this manifest was introduced.
INSERT IGNORE INTO memory_privacy_deletion
    (context_id, source_event_id, source_hash, action_kind, cleanup_state, attempts, last_error,
     requested_at, updated_at, completed_at)
SELECT j.context_id, j.source_event_id, m.content_hash,
       CASE WHEN m.state IN ('DELETION_PENDING', 'DELETED') THEN 'DELETE' ELSE 'WITHDRAW' END,
       CASE WHEN j.state = 'SUCCEEDED' THEN 'COMPLETE'
            WHEN j.state = 'PROCESSING' THEN 'PROCESSING'
            WHEN j.state = 'RETRY' THEN 'RETRY'
            WHEN j.state = 'FAILED' THEN 'FAILED'
            ELSE 'PENDING' END,
       j.attempts, j.last_error, j.available_at, j.available_at,
       CASE WHEN j.state = 'SUCCEEDED' THEN j.available_at ELSE NULL END
FROM memory_processing_job j
JOIN memory_message m ON m.context_id = j.context_id AND m.source_event_id = j.source_event_id
WHERE j.job_kind = 'PRIVACY_CLEANUP';
