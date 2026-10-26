SELECT (SELECT COUNT(*) FROM bk_auth.auth_permission
        WHERE permission_code='interview.read.all' AND domain='interview'
          AND operation='read.all' AND active=1 AND version>=2)=1
   AND (SELECT COUNT(*) FROM bk_auth.auth_authorization_migration
        WHERE migration_id='20261026_interview_read_all_permission')=1;
