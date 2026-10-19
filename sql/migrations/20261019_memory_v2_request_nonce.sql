-- MySQL 8.4; memory v2 single-use request proofs. Never execute against another domain.
USE bk_memory;

CREATE TABLE IF NOT EXISTS memory_v2_request_nonce (
    key_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    nonce VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (key_id, nonce),
    KEY idx_memory_v2_nonce_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
