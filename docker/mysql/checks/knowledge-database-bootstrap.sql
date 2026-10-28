SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.schemata WHERE schema_name='bk_knowledge')
    AND EXISTS(SELECT 1 FROM mysql.user WHERE user='knowledge_source_view_owner'
        AND host='%' AND account_locked='Y')
    AND (SELECT COUNT(*) FROM information_schema.column_privileges
        WHERE grantee='''knowledge_source_view_owner''@''%'''
          AND table_schema='bk_interview'
          AND ((table_name='interview_asset' AND column_name IN
                ('id','case_id','session_id','source','title','content','snapshot_hash','archive_grade',
                 'content_hash','content_revision','sanitization_revision','visibility','publication_revision','deleted'))
            OR (table_name='interview_case' AND column_name IN ('id','deleted'))
            OR (table_name='interview_session' AND column_name IN ('id','case_id','status','deleted'))))=20
    AND NOT EXISTS(SELECT 1 FROM information_schema.column_privileges
        WHERE grantee='''knowledge_source_view_owner''@''%'''
          AND table_schema='bk_interview' AND column_name='raw_content'),
    1, 0);
