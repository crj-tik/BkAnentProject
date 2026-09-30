-- create-interview-subagent 补齐：导演指令持久化（人工操作留痕 + 运行面消费）
USE bk_interview;

CREATE TABLE IF NOT EXISTS interview_director_command (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    command VARCHAR(24) NOT NULL COMMENT 'WRAP_UP|NEXT_QUESTION|PINNED_QUESTION',
    pinned_question TEXT NULL COMMENT 'PINNED_QUESTION 的逐字播出文本',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING|CONSUMED',
    source VARCHAR(16) NOT NULL DEFAULT 'A2A' COMMENT 'A2A|DIRECT_REST',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_interview_director_command_session (session_id, status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
