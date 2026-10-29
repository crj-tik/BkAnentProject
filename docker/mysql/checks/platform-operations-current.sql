SELECT IF(
    (SELECT COUNT(*) FROM information_schema.tables
     WHERE table_schema='bk_platform_operations' AND table_name IN
       ('employee_kpi_stat','employee_daily_workload','listing_turnover_stat','store_dashboard_snapshot',
        'store_review_matter','store_review_evidence','store_review_diagnosis_revision',
        'store_review_plan_revision','store_review_action','store_review_action_receipt',
        'store_review_idempotency','store_review_observation',
        'knowledge_case','knowledge_revision','knowledge_source_ref','knowledge_review_decision',
        'knowledge_operation_receipt','knowledge_generation_task',
        'a2a_request_nonce','a2a_task_owner','a2a_task_execution_reservation'))=21
    AND EXISTS(SELECT 1 FROM information_schema.views
        WHERE table_schema='bk_platform_operations' AND table_name='knowledge_interview_public_source_v1'
          AND security_type='DEFINER' AND definer='platform_operations_view_owner@%')
    AND NOT EXISTS(SELECT 1 FROM information_schema.columns
        WHERE table_schema='bk_platform_operations' AND table_name='knowledge_interview_public_source_v1'
          AND column_name IN ('raw_content','rawContent','grade_basis','metadata_json'))
    AND EXISTS(SELECT 1 FROM mysql.user WHERE user='platform_operations_app' AND host='%')
    AND EXISTS(SELECT 1 FROM mysql.user WHERE user='platform_operations_source_ro' AND host='%')
    AND (SELECT COUNT(*) FROM information_schema.table_privileges
        WHERE grantee='''platform_operations_app''@''%''' AND table_schema='bk_platform_operations')=48
    AND (SELECT COUNT(*) FROM information_schema.table_privileges
        WHERE grantee='''platform_operations_source_ro''@''%''' AND table_schema='bk_platform_operations'
          AND table_name='knowledge_interview_public_source_v1' AND privilege_type='SELECT')=1
    AND (SELECT COUNT(*) FROM information_schema.table_privileges
        WHERE grantee='''platform_operations_source_ro''@''%''' AND table_schema='bk_platform_operations')=1
    AND NOT EXISTS(SELECT 1 FROM information_schema.schema_privileges
        WHERE grantee IN ('''platform_operations_app''@''%''','''platform_operations_source_ro''@''%''')
          AND table_schema IN ('bk_platform_operations','bk_interview'))
    AND NOT EXISTS(SELECT 1 FROM information_schema.table_privileges
        WHERE grantee IN ('''platform_operations_app''@''%''','''platform_operations_source_ro''@''%''')
          AND table_schema='bk_interview')
    AND NOT EXISTS(SELECT 1 FROM information_schema.column_privileges
        WHERE grantee IN ('''platform_operations_app''@''%''','''platform_operations_source_ro''@''%''')
          AND table_schema IN ('bk_platform_operations','bk_interview'))
    AND NOT EXISTS(SELECT 1 FROM information_schema.user_privileges
        WHERE grantee IN ('''platform_operations_app''@''%''','''platform_operations_source_ro''@''%''',
                          '''platform_operations_view_owner''@''%''') AND privilege_type <> 'USAGE'),
    1, 0
);
