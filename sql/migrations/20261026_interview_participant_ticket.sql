-- Per-user, per-session and per-purpose short-lived credentials. Raw values are never stored.
USE bk_interview;

CREATE TABLE IF NOT EXISTS interview_participant_ticket (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    purpose VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'MANAGE|RESPOND|READ',
    ticket_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at DATETIME NOT NULL,
    revoked_at DATETIME NULL,
    revision INT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_interview_participant_ticket_hash (ticket_hash),
    KEY idx_interview_participant_ticket_owner (session_id, user_id, purpose, revoked_at, expires_at),
    CONSTRAINT chk_interview_participant_ticket_purpose CHECK (purpose IN ('MANAGE', 'RESPOND', 'READ')),
    CONSTRAINT chk_interview_participant_ticket_ids CHECK (session_id > 0 AND user_id > 0),
    CONSTRAINT chk_interview_participant_ticket_revision CHECK (revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
