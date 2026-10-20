USE bk_memory;
CREATE TABLE IF NOT EXISTS memory_conversation_writer (
    context_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    source_caller VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    acquire_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fence BIGINT NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    CHECK (fence > 0 AND expires_at_ms >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
