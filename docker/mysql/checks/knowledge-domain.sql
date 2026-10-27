SELECT IF(
    (SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema='bk_knowledge' AND table_name IN
            ('knowledge_case','knowledge_revision','knowledge_source_ref','knowledge_review_decision',
             'knowledge_operation_receipt','knowledge_generation_task','knowledge_interview_public_source_v1'))=7
    AND EXISTS(SELECT 1 FROM information_schema.views
        WHERE table_schema='bk_knowledge' AND table_name='knowledge_interview_public_source_v1'
          AND security_type='DEFINER' AND definer='knowledge_source_view_owner@%')
    AND (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema='bk_knowledge' AND table_name='knowledge_interview_public_source_v1'
          AND column_name IN ('asset_id','case_id','session_id','source','title','snapshot_hash','archive_grade',
              'content_hash','content_revision','sanitization_revision','publication_revision','content','source_relation'))=13
    AND NOT EXISTS(SELECT 1 FROM information_schema.columns
        WHERE table_schema='bk_knowledge' AND table_name='knowledge_interview_public_source_v1'
          AND column_name IN ('raw_content','rawContent','grade_basis','metadata_json'))
    AND EXISTS(SELECT 1 FROM mysql.user WHERE user='knowledge_app' AND host='%')
    AND EXISTS(SELECT 1 FROM mysql.user WHERE user='knowledge_source_ro' AND host='%')
    AND (SELECT COUNT(*) FROM information_schema.table_privileges
        WHERE grantee='''knowledge_app''@''%''' AND table_schema='bk_knowledge')=14
    AND NOT EXISTS(SELECT 1 FROM information_schema.schema_privileges
        WHERE grantee='''knowledge_app''@''%''' AND table_schema IN ('bk_knowledge','bk_interview'))
    AND NOT EXISTS(SELECT 1 FROM information_schema.column_privileges
        WHERE grantee='''knowledge_app''@''%''' AND table_schema IN ('bk_knowledge','bk_interview'))
    AND NOT EXISTS(SELECT 1 FROM information_schema.table_privileges
        WHERE grantee='''knowledge_app''@''%''' AND table_schema='bk_interview')
    AND (SELECT COUNT(*) FROM information_schema.table_privileges
        WHERE grantee='''knowledge_source_ro''@''%''' AND table_schema='bk_knowledge'
          AND table_name='knowledge_interview_public_source_v1' AND privilege_type='SELECT')=1
    AND (SELECT COUNT(*) FROM information_schema.table_privileges
        WHERE grantee='''knowledge_source_ro''@''%''' AND table_schema='bk_knowledge')=1
    AND NOT EXISTS(SELECT 1 FROM information_schema.schema_privileges
        WHERE grantee='''knowledge_source_ro''@''%''' AND table_schema IN ('bk_knowledge','bk_interview'))
    AND NOT EXISTS(SELECT 1 FROM information_schema.column_privileges
        WHERE grantee='''knowledge_source_ro''@''%''' AND table_schema IN ('bk_knowledge','bk_interview'))
    AND NOT EXISTS(SELECT 1 FROM information_schema.table_privileges
        WHERE grantee='''knowledge_source_ro''@''%''' AND table_schema='bk_interview'),
    1, 0);
