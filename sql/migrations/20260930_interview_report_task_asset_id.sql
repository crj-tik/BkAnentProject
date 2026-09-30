-- Keep the interview report task table aligned with InterviewReportTaskEntity.
-- This is safe to run against both an existing database and a fresh bootstrap.
SET @interview_asset_id_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = 'bk_interview'
      AND table_name = 'interview_report_task'
      AND column_name = 'asset_id'
);

SET @interview_asset_id_migration = IF(
    @interview_asset_id_exists = 0,
    'ALTER TABLE bk_interview.interview_report_task ADD COLUMN asset_id BIGINT NULL AFTER session_id',
    'SELECT 1'
);

PREPARE interview_asset_id_statement FROM @interview_asset_id_migration;
EXECUTE interview_asset_id_statement;
DEALLOCATE PREPARE interview_asset_id_statement;
