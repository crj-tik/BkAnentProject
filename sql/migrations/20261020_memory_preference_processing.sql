USE bk_memory;

-- Immutable assessment receipt. Raw messages remain only in the archive; this table has derived data and a locator.
CREATE TABLE IF NOT EXISTS memory_preference_evaluation (
    job_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    source_json LONGTEXT NOT NULL,
    result_json LONGTEXT NULL,
    result_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    extraction_version VARCHAR(255) NOT NULL,
    assessed_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
