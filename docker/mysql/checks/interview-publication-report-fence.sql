SELECT (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema='bk_interview' AND table_name='interview_asset'
          AND column_name IN ('raw_content','content_hash','content_revision','sanitization_revision',
                              'visibility','publication_revision','approved_by_user_id','published_at'))=8
   AND (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema='bk_interview' AND table_name='interview_report_task'
          AND column_name IN ('source_content_hash','source_revision','source_sanitization_revision','idempotency_key',
                              'template_version','requested_by_user_id','permission_revision','credential_epoch',
                              'authorization_expires_at','business_wide'))=10
   AND (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema='bk_interview' AND table_name='interview_report'
          AND column_name IN ('source_content_hash','source_revision','template_version','task_id'))=4
   AND EXISTS (SELECT 1 FROM information_schema.statistics
               WHERE table_schema='bk_interview' AND table_name='interview_report'
                 AND index_name='uk_interview_report_task' AND non_unique=0 AND column_name='task_id')
   AND EXISTS (SELECT 1 FROM information_schema.statistics
               WHERE table_schema='bk_interview' AND table_name='interview_report_task'
                 AND index_name='uk_interview_report_task_idempotency' AND non_unique=0
                 AND column_name='idempotency_key');
