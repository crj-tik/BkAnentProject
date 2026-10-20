USE bk_agent;
-- References into immutable existing graph checkpoints, never another copy of tool/business bodies.
CREATE TABLE IF NOT EXISTS agent_memory_cursor (
    context_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    writer_fence BIGINT NOT NULL,
    last_sequence BIGINT NOT NULL,
    CHECK (writer_fence > 0 AND last_sequence >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS agent_memory_delivery (
    context_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    checkpoint_version INT NOT NULL,
    message_ordinal INT NOT NULL,
    sequence_no BIGINT NOT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    permit_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    last_error VARCHAR(64) NULL,
    PRIMARY KEY (context_id,event_id),
    UNIQUE KEY uk_agent_memory_sequence (context_id,sequence_no),
    KEY idx_agent_memory_pending (context_id,state,sequence_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
