SELECT IF(
    (SELECT COUNT(*) FROM information_schema.tables
     WHERE table_schema = DATABASE()
       AND table_name IN (
         'store_review_matter',
         'store_review_evidence',
         'store_review_diagnosis_revision',
         'store_review_plan_revision',
         'store_review_action',
         'store_review_action_receipt',
         'store_review_idempotency',
         'store_review_observation'
       )) = 8
    AND EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'store_review_matter'
                  AND column_name = 'store_id' AND data_type = 'bigint' AND is_nullable = 'NO')
    AND EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'store_review_evidence'
                  AND column_name = 'event_time' AND is_nullable = 'YES')
    AND EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'store_review_action'
                  AND column_name = 'feedback_note' AND is_nullable = 'YES')
    AND EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'store_review_action'
                  AND column_name = 'action_version' AND data_type = 'bigint' AND is_nullable = 'NO')
    AND EXISTS (SELECT 1 FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'store_review_plan_revision'
                  AND index_name = 'PRIMARY' AND column_name = 'plan_version')
    AND EXISTS (SELECT 1 FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'store_review_idempotency'
                  AND index_name = 'PRIMARY' AND column_name = 'request_id'),
    1, 0
);
