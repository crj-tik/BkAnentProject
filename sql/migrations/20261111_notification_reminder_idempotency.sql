-- Idempotent delivery key for narrowly scoped, service-signed background reminders.
USE bk_notification;

SET @reminder_column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema='bk_notification' AND table_name='notification_record'
      AND column_name='idempotency_key'
);
SET @reminder_column_sql = IF(@reminder_column_exists=0,
    'ALTER TABLE notification_record ADD COLUMN idempotency_key VARCHAR(128) NULL AFTER error_message',
    'SELECT 1');
PREPARE reminder_column_statement FROM @reminder_column_sql;
EXECUTE reminder_column_statement;
DEALLOCATE PREPARE reminder_column_statement;

SET @reminder_index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema='bk_notification' AND table_name='notification_record'
      AND index_name='uk_notification_record_idempotency_key'
);
SET @reminder_index_sql = IF(@reminder_index_exists=0,
    'CREATE UNIQUE INDEX uk_notification_record_idempotency_key ON notification_record(idempotency_key)',
    'SELECT 1');
PREPARE reminder_index_statement FROM @reminder_index_sql;
EXECUTE reminder_index_statement;
DEALLOCATE PREPARE reminder_index_statement;
