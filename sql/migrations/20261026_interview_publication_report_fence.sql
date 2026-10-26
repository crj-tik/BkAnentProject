-- Private-by-default sanitized projection and source/lease fences for Interview assets and reports.
USE bk_interview;

SET @bk_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='bk_interview'
    AND table_name='interview_asset' AND column_name='raw_content'), 'SELECT 1',
    'ALTER TABLE bk_interview.interview_asset ADD COLUMN raw_content LONGTEXT NULL');
PREPARE bk_stmt FROM @bk_ddl; EXECUTE bk_stmt; DEALLOCATE PREPARE bk_stmt;

SET @bk_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='bk_interview'
    AND table_name='interview_asset' AND column_name='content_hash'), 'SELECT 1',
    'ALTER TABLE bk_interview.interview_asset ADD COLUMN content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL');
PREPARE bk_stmt FROM @bk_ddl; EXECUTE bk_stmt; DEALLOCATE PREPARE bk_stmt;

SET @bk_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='bk_interview'
    AND table_name='interview_asset' AND column_name='content_revision'), 'SELECT 1',
    'ALTER TABLE bk_interview.interview_asset ADD COLUMN content_revision INT NOT NULL DEFAULT 0');
PREPARE bk_stmt FROM @bk_ddl; EXECUTE bk_stmt; DEALLOCATE PREPARE bk_stmt;

SET @bk_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='bk_interview'
    AND table_name='interview_asset' AND column_name='sanitization_revision'), 'SELECT 1',
    'ALTER TABLE bk_interview.interview_asset ADD COLUMN sanitization_revision INT NOT NULL DEFAULT 0');
PREPARE bk_stmt FROM @bk_ddl; EXECUTE bk_stmt; DEALLOCATE PREPARE bk_stmt;

SET @bk_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='bk_interview'
    AND table_name='interview_asset' AND column_name='visibility'), 'SELECT 1',
    'ALTER TABLE bk_interview.interview_asset ADD COLUMN visibility VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT ''PRIVATE''');
PREPARE bk_stmt FROM @bk_ddl; EXECUTE bk_stmt; DEALLOCATE PREPARE bk_stmt;

SET @bk_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='bk_interview'
    AND table_name='interview_asset' AND column_name='publication_revision'), 'SELECT 1',
    'ALTER TABLE bk_interview.interview_asset ADD COLUMN publication_revision INT NOT NULL DEFAULT 0');
PREPARE bk_stmt FROM @bk_ddl; EXECUTE bk_stmt; DEALLOCATE PREPARE bk_stmt;

SET @bk_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='bk_interview'
    AND table_name='interview_asset' AND column_name='approved_by_user_id'), 'SELECT 1',
    'ALTER TABLE bk_interview.interview_asset ADD COLUMN approved_by_user_id BIGINT NULL');
PREPARE bk_stmt FROM @bk_ddl; EXECUTE bk_stmt; DEALLOCATE PREPARE bk_stmt;

SET @bk_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='bk_interview'
    AND table_name='interview_asset' AND column_name='published_at'), 'SELECT 1',
    'ALTER TABLE bk_interview.interview_asset ADD COLUMN published_at DATETIME NULL');
PREPARE bk_stmt FROM @bk_ddl; EXECUTE bk_stmt; DEALLOCATE PREPARE bk_stmt;

SET @bk_ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema='bk_interview'
    AND table_name='interview_asset' AND index_name='idx_interview_asset_public'), 'SELECT 1',
    'CREATE INDEX idx_interview_asset_public ON bk_interview.interview_asset (visibility, archive_grade, deleted, id)');
PREPARE bk_stmt FROM @bk_ddl; EXECUTE bk_stmt; DEALLOCATE PREPARE bk_stmt;

SET @bk_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='bk_interview'
    AND table_name='interview_report_task' AND column_name='source_content_hash'), 'SELECT 1',
    'ALTER TABLE bk_interview.interview_report_task ADD COLUMN source_content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL');
PREPARE bk_stmt FROM @bk_ddl; EXECUTE bk_stmt; DEALLOCATE PREPARE bk_stmt;

SET @bk_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='bk_interview'
    AND table_name='interview_report_task' AND column_name='source_revision'), 'SELECT 1',
    'ALTER TABLE bk_interview.interview_report_task ADD COLUMN source_revision INT NOT NULL DEFAULT 0');
PREPARE bk_stmt FROM @bk_ddl; EXECUTE bk_stmt; DEALLOCATE PREPARE bk_stmt;

SET @bk_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='bk_interview'
    AND table_name='interview_report_task' AND column_name='source_sanitization_revision'), 'SELECT 1',
    'ALTER TABLE bk_interview.interview_report_task ADD COLUMN source_sanitization_revision INT NOT NULL DEFAULT 0');
PREPARE bk_stmt FROM @bk_ddl; EXECUTE bk_stmt; DEALLOCATE PREPARE bk_stmt;

SET @bk_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='bk_interview'
    AND table_name='interview_report_task' AND column_name='idempotency_key'), 'SELECT 1',
    'ALTER TABLE bk_interview.interview_report_task ADD COLUMN idempotency_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL');
PREPARE bk_stmt FROM @bk_ddl; EXECUTE bk_stmt; DEALLOCATE PREPARE bk_stmt;

SET @bk_ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema='bk_interview'
    AND table_name='interview_report_task' AND index_name='uk_interview_report_task_idempotency'), 'SELECT 1',
    'CREATE UNIQUE INDEX uk_interview_report_task_idempotency ON bk_interview.interview_report_task (idempotency_key)');
PREPARE bk_stmt FROM @bk_ddl; EXECUTE bk_stmt; DEALLOCATE PREPARE bk_stmt;

SET @bk_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='bk_interview'
    AND table_name='interview_report_task' AND column_name='template_version'), 'SELECT 1',
    'ALTER TABLE bk_interview.interview_report_task ADD COLUMN template_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL');
PREPARE bk_stmt FROM @bk_ddl; EXECUTE bk_stmt; DEALLOCATE PREPARE bk_stmt;

SET @bk_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='bk_interview'
    AND table_name='interview_report_task' AND column_name='requested_by_user_id'), 'SELECT 1',
    'ALTER TABLE bk_interview.interview_report_task ADD COLUMN requested_by_user_id BIGINT NULL');
PREPARE bk_stmt FROM @bk_ddl; EXECUTE bk_stmt; DEALLOCATE PREPARE bk_stmt;

SET @bk_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='bk_interview'
    AND table_name='interview_report_task' AND column_name='permission_revision'), 'SELECT 1',
    'ALTER TABLE bk_interview.interview_report_task ADD COLUMN permission_revision BIGINT NULL');
PREPARE bk_stmt FROM @bk_ddl; EXECUTE bk_stmt; DEALLOCATE PREPARE bk_stmt;

SET @bk_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='bk_interview'
    AND table_name='interview_report_task' AND column_name='credential_epoch'), 'SELECT 1',
    'ALTER TABLE bk_interview.interview_report_task ADD COLUMN credential_epoch BIGINT NULL');
PREPARE bk_stmt FROM @bk_ddl; EXECUTE bk_stmt; DEALLOCATE PREPARE bk_stmt;

SET @bk_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='bk_interview'
    AND table_name='interview_report_task' AND column_name='authorization_expires_at'), 'SELECT 1',
    'ALTER TABLE bk_interview.interview_report_task ADD COLUMN authorization_expires_at DATETIME NULL');
PREPARE bk_stmt FROM @bk_ddl; EXECUTE bk_stmt; DEALLOCATE PREPARE bk_stmt;

SET @bk_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='bk_interview'
    AND table_name='interview_report_task' AND column_name='business_wide'), 'SELECT 1',
    'ALTER TABLE bk_interview.interview_report_task ADD COLUMN business_wide TINYINT NOT NULL DEFAULT 0');
PREPARE bk_stmt FROM @bk_ddl; EXECUTE bk_stmt; DEALLOCATE PREPARE bk_stmt;

SET @bk_ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema='bk_interview'
    AND table_name='interview_report_task' AND index_name='idx_interview_report_task_source'), 'SELECT 1',
    'CREATE INDEX idx_interview_report_task_source ON bk_interview.interview_report_task (asset_id, source_revision, status)');
PREPARE bk_stmt FROM @bk_ddl; EXECUTE bk_stmt; DEALLOCATE PREPARE bk_stmt;

SET @bk_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='bk_interview'
    AND table_name='interview_report' AND column_name='source_content_hash'), 'SELECT 1',
    'ALTER TABLE bk_interview.interview_report ADD COLUMN source_content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL');
PREPARE bk_stmt FROM @bk_ddl; EXECUTE bk_stmt; DEALLOCATE PREPARE bk_stmt;

SET @bk_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='bk_interview'
    AND table_name='interview_report' AND column_name='source_revision'), 'SELECT 1',
    'ALTER TABLE bk_interview.interview_report ADD COLUMN source_revision INT NOT NULL DEFAULT 0');
PREPARE bk_stmt FROM @bk_ddl; EXECUTE bk_stmt; DEALLOCATE PREPARE bk_stmt;

SET @bk_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='bk_interview'
    AND table_name='interview_report' AND column_name='template_version'), 'SELECT 1',
    'ALTER TABLE bk_interview.interview_report ADD COLUMN template_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL');
PREPARE bk_stmt FROM @bk_ddl; EXECUTE bk_stmt; DEALLOCATE PREPARE bk_stmt;

SET @bk_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='bk_interview'
    AND table_name='interview_report' AND column_name='task_id'), 'SELECT 1',
    'ALTER TABLE bk_interview.interview_report ADD COLUMN task_id BIGINT NULL');
PREPARE bk_stmt FROM @bk_ddl; EXECUTE bk_stmt; DEALLOCATE PREPARE bk_stmt;

UPDATE bk_interview.interview_report r
JOIN bk_interview.interview_report_task t ON t.report_id = r.id
SET r.task_id = t.id
WHERE r.task_id IS NULL;

SET @bk_ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema='bk_interview'
    AND table_name='interview_report' AND index_name='uk_interview_report_task'), 'SELECT 1',
    'CREATE UNIQUE INDEX uk_interview_report_task ON bk_interview.interview_report (task_id)');
PREPARE bk_stmt FROM @bk_ddl; EXECUTE bk_stmt; DEALLOCATE PREPARE bk_stmt;

-- Old pending jobs were keyed only by case/asset ids; require explicit resubmission with a source snapshot.
UPDATE bk_interview.interview_report_task
SET status='BLOCKED', error_class='blocked', error_message='legacy task requires resubmission with verified source snapshot',
    lease_owner=NULL, lease_expires_at=NULL
WHERE status IN ('PENDING', 'RETRYABLE', 'RUNNING') AND source_content_hash IS NULL;
