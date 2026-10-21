USE bk_memory;

CREATE TABLE IF NOT EXISTS memory_matter_revision (
    context_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    matter_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_revision BIGINT NOT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_json LONGTEXT NULL,
    PRIMARY KEY(context_id,matter_id,content_revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS memory_matter_receipt (
    context_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_event_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    intent_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    response_json LONGTEXT NULL,
    PRIMARY KEY(context_id,source_event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
