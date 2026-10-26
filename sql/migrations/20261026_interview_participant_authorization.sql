-- Add trusted user ownership and per-session participant relationships without guessing legacy users.
USE bk_interview;

SET @bk_ddl = IF(EXISTS(
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'bk_interview' AND table_name = 'interview_case' AND column_name = 'created_by_user_id'
), 'SELECT 1', 'ALTER TABLE bk_interview.interview_case ADD COLUMN created_by_user_id BIGINT NULL AFTER creator_work_no');
PREPARE bk_stmt FROM @bk_ddl;
EXECUTE bk_stmt;
DEALLOCATE PREPARE bk_stmt;

SET @bk_ddl = IF(EXISTS(
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'bk_interview' AND table_name = 'interview_session' AND column_name = 'created_by_user_id'
), 'SELECT 1', 'ALTER TABLE bk_interview.interview_session ADD COLUMN created_by_user_id BIGINT NULL AFTER creator_work_no');
PREPARE bk_stmt FROM @bk_ddl;
EXECUTE bk_stmt;
DEALLOCATE PREPARE bk_stmt;

SET @bk_ddl = IF(EXISTS(
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = 'bk_interview' AND table_name = 'interview_case' AND index_name = 'idx_interview_case_created_by_user'
), 'SELECT 1', 'CREATE INDEX idx_interview_case_created_by_user ON bk_interview.interview_case (created_by_user_id, deleted)');
PREPARE bk_stmt FROM @bk_ddl;
EXECUTE bk_stmt;
DEALLOCATE PREPARE bk_stmt;

SET @bk_ddl = IF(EXISTS(
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = 'bk_interview' AND table_name = 'interview_session' AND index_name = 'idx_interview_session_created_by_user'
), 'SELECT 1', 'CREATE INDEX idx_interview_session_created_by_user ON bk_interview.interview_session (created_by_user_id, deleted)');
PREPARE bk_stmt FROM @bk_ddl;
EXECUTE bk_stmt;
DEALLOCATE PREPARE bk_stmt;

CREATE TABLE IF NOT EXISTS interview_participant (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    relation VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'CREATE|RESPOND; resource relation, not role',
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'PENDING|CONFIRMED|REVOKED',
    invited_by_user_id BIGINT NOT NULL,
    confirmed_at DATETIME NULL,
    revision INT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_interview_participant_relation (session_id, user_id, relation),
    KEY idx_interview_participant_user_state (user_id, state, relation, session_id),
    KEY idx_interview_participant_session_state (session_id, state, relation),
    CONSTRAINT chk_interview_participant_relation CHECK (relation IN ('CREATE', 'RESPOND')),
    CONSTRAINT chk_interview_participant_state CHECK (state IN ('PENDING', 'CONFIRMED', 'REVOKED')),
    CONSTRAINT chk_interview_participant_ids CHECK (session_id > 0 AND user_id > 0 AND invited_by_user_id > 0),
    CONSTRAINT chk_interview_participant_revision CHECK (revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Preserve who requested a deferred collaboration command and the exact signed-context window.
SET @bk_ddl = IF(EXISTS(
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'bk_interview' AND table_name = 'interview_director_command'
      AND column_name = 'requested_by_user_id'
), 'SELECT 1', 'ALTER TABLE bk_interview.interview_director_command ADD COLUMN requested_by_user_id BIGINT NULL');
PREPARE bk_stmt FROM @bk_ddl;
EXECUTE bk_stmt;
DEALLOCATE PREPARE bk_stmt;

SET @bk_ddl = IF(EXISTS(
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'bk_interview' AND table_name = 'interview_director_command'
      AND column_name = 'permission_revision'
), 'SELECT 1', 'ALTER TABLE bk_interview.interview_director_command ADD COLUMN permission_revision BIGINT NULL');
PREPARE bk_stmt FROM @bk_ddl;
EXECUTE bk_stmt;
DEALLOCATE PREPARE bk_stmt;

SET @bk_ddl = IF(EXISTS(
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'bk_interview' AND table_name = 'interview_director_command'
      AND column_name = 'credential_epoch'
), 'SELECT 1', 'ALTER TABLE bk_interview.interview_director_command ADD COLUMN credential_epoch BIGINT NULL');
PREPARE bk_stmt FROM @bk_ddl;
EXECUTE bk_stmt;
DEALLOCATE PREPARE bk_stmt;

SET @bk_ddl = IF(EXISTS(
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'bk_interview' AND table_name = 'interview_director_command'
      AND column_name = 'authorization_expires_at'
), 'SELECT 1', 'ALTER TABLE bk_interview.interview_director_command ADD COLUMN authorization_expires_at DATETIME NULL');
PREPARE bk_stmt FROM @bk_ddl;
EXECUTE bk_stmt;
DEALLOCATE PREPARE bk_stmt;

SET @bk_ddl = IF(EXISTS(
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'bk_interview' AND table_name = 'interview_director_command'
      AND column_name = 'business_wide'
), 'SELECT 1', 'ALTER TABLE bk_interview.interview_director_command ADD COLUMN business_wide TINYINT NOT NULL DEFAULT 0');
PREPARE bk_stmt FROM @bk_ddl;
EXECUTE bk_stmt;
DEALLOCATE PREPARE bk_stmt;

SET @bk_ddl = IF(EXISTS(
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'bk_interview' AND table_name = 'interview_director_command'
      AND column_name = 'authorization_operation'
), 'SELECT 1', 'ALTER TABLE bk_interview.interview_director_command ADD COLUMN authorization_operation VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL');
PREPARE bk_stmt FROM @bk_ddl;
EXECUTE bk_stmt;
DEALLOCATE PREPARE bk_stmt;

SET @bk_ddl = IF(EXISTS(
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = 'bk_interview' AND table_name = 'interview_director_command'
      AND index_name = 'idx_interview_director_auth_actor'
), 'SELECT 1', 'CREATE INDEX idx_interview_director_auth_actor ON bk_interview.interview_director_command (session_id, requested_by_user_id, status)');
PREPARE bk_stmt FROM @bk_ddl;
EXECUTE bk_stmt;
DEALLOCATE PREPARE bk_stmt;
