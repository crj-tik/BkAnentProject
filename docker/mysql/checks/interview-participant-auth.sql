SELECT (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema='bk_interview' AND table_name IN ('interview_case','interview_session')
          AND column_name='created_by_user_id' AND data_type='bigint' AND is_nullable='YES')=2
   AND (SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema='bk_interview' AND table_name='interview_participant' AND engine='InnoDB')=1
   AND (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema='bk_interview' AND table_name='interview_director_command'
          AND column_name IN ('requested_by_user_id','permission_revision','credential_epoch',
                              'authorization_expires_at','business_wide','authorization_operation'))=6;
