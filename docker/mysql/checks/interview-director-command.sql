SELECT (SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema='bk_interview' AND table_name='interview_director_command' AND engine='InnoDB')=1
   AND (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema='bk_interview' AND table_name='interview_director_command'
          AND column_name IN ('id','session_id','command','pinned_question','status','source',
                              'created_at','updated_at','deleted'))=9
   AND (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
        WHERE table_schema='bk_interview' AND table_name='interview_director_command'
          AND index_name='idx_interview_director_command_session')=1;
