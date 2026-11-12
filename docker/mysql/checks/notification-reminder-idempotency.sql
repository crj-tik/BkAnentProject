USE bk_notification;

SELECT
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema='bk_notification' AND table_name='notification_record'
       AND column_name='idempotency_key' AND column_type='varchar(128)' AND is_nullable='YES') = 1
    AND (SELECT COUNT(*) FROM information_schema.statistics
         WHERE table_schema='bk_notification' AND table_name='notification_record'
           AND index_name='uk_notification_record_idempotency_key' AND non_unique=0) = 1;
