SELECT IF(
    EXISTS(SELECT 1 FROM mysql.user WHERE user='platform_operations_view_owner' AND host='%' AND account_locked='Y')
    AND EXISTS(SELECT 1 FROM information_schema.views
        WHERE table_schema='bk_platform_operations' AND table_name='knowledge_interview_public_source_v1'
          AND security_type='DEFINER' AND definer='platform_operations_view_owner@%')
    AND (SELECT COUNT(*) FROM information_schema.column_privileges
        WHERE grantee='''platform_operations_view_owner''@''%''' AND table_schema='bk_interview'
          AND privilege_type='SELECT')=20
    AND NOT EXISTS(SELECT 1 FROM information_schema.column_privileges
        WHERE grantee='''platform_operations_view_owner''@''%''' AND table_schema='bk_interview'
          AND column_name IN ('raw_content','rawContent','grade_basis','metadata_json'))
    AND NOT EXISTS(SELECT 1 FROM information_schema.schema_privileges
        WHERE grantee='''platform_operations_view_owner''@''%''' AND table_schema='bk_interview')
    AND NOT EXISTS(SELECT 1 FROM information_schema.table_privileges
        WHERE grantee='''platform_operations_view_owner''@''%''' AND table_schema='bk_interview'),
    1, 0
);
