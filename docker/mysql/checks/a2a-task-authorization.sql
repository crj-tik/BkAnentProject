SELECT (SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema=DATABASE() AND engine='InnoDB'
          AND table_name IN ('a2a_request_nonce','a2a_task_owner','a2a_task_execution_reservation'))=3
   AND (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='a2a_request_nonce'
          AND column_name IN ('nonce','expires_at','created_at'))=3
   AND (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='a2a_task_owner'
          AND column_name IN ('task_id','actor_id','caller_service','context_id','run_id','session_id','created_at'))=7
   AND (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='a2a_task_execution_reservation'
          AND column_name IN ('task_id','actor_id','request_nonce','reserved_until','created_at'))=5
   AND EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema=DATABASE() AND table_name='a2a_request_nonce'
                 AND column_name='nonce' AND data_type='char' AND character_maximum_length=43
                 AND collation_name='ascii_bin' AND is_nullable='NO')
   AND EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema=DATABASE() AND table_name='a2a_task_owner'
                 AND column_name='task_id' AND data_type='varchar' AND character_maximum_length=128
                 AND collation_name='utf8mb4_bin' AND is_nullable='NO')
   AND EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema=DATABASE() AND table_name='a2a_task_owner'
                 AND column_name='context_id' AND is_nullable='YES')
   AND EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema=DATABASE() AND table_name='a2a_task_execution_reservation'
                 AND column_name='request_nonce' AND data_type='char' AND character_maximum_length=43
                 AND collation_name='ascii_bin' AND is_nullable='NO')
   AND EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema=DATABASE() AND table_name='a2a_task_execution_reservation'
                 AND column_name='task_id' AND data_type='varchar' AND character_maximum_length=128
                 AND collation_name='utf8mb4_bin' AND is_nullable='NO')
   AND EXISTS (SELECT 1 FROM information_schema.statistics
               WHERE table_schema=DATABASE() AND table_name='a2a_request_nonce'
                 AND index_name='PRIMARY' AND non_unique=0 AND column_name='nonce')
   AND EXISTS (SELECT 1 FROM information_schema.statistics
               WHERE table_schema=DATABASE() AND table_name='a2a_task_owner'
                 AND index_name='PRIMARY' AND non_unique=0 AND column_name='task_id')
   AND EXISTS (SELECT 1 FROM information_schema.statistics
               WHERE table_schema=DATABASE() AND table_name='a2a_task_execution_reservation'
                 AND index_name='PRIMARY' AND non_unique=0 AND column_name='task_id')
   AND EXISTS (SELECT 1 FROM information_schema.statistics
               WHERE table_schema=DATABASE() AND table_name='a2a_task_execution_reservation'
                 AND index_name='uk_a2a_task_reservation_nonce' AND non_unique=0 AND column_name='request_nonce');
