SELECT (SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema='bk_interview' AND table_name='interview_participant_ticket'
          AND engine='InnoDB')=1
   AND (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema='bk_interview' AND table_name='interview_participant_ticket'
          AND column_name IN ('session_id','user_id','purpose','ticket_hash','expires_at',
                              'revoked_at','revision'))=7
   AND EXISTS (SELECT 1 FROM information_schema.statistics
               WHERE table_schema='bk_interview' AND table_name='interview_participant_ticket'
                 AND index_name='uk_interview_participant_ticket_hash' AND non_unique=0
                 AND column_name='ticket_hash')
   AND EXISTS (SELECT 1 FROM information_schema.statistics
               WHERE table_schema='bk_interview' AND table_name='interview_participant_ticket'
                 AND index_name='idx_interview_participant_ticket_owner'
                 AND column_name='session_id' AND seq_in_index=1);
