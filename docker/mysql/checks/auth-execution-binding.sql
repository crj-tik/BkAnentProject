SELECT (SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema=DATABASE() AND table_name='auth_authorization_execution_binding'
          AND engine='InnoDB')=1
   AND (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='auth_authorization_execution_binding'
          AND column_name IN ('ingress_request_hash','tuple_hash','actor_id','caller','run_id','session_id',
                              'policy_digest','operations_hash','execution_hash','expires_at','hard_deadline','created_at'))=12
   AND (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='auth_authorization_execution_binding'
          AND column_name IN ('ingress_request_hash','tuple_hash','policy_digest','operations_hash','execution_hash')
          AND data_type='char' AND character_maximum_length=64 AND collation_name='ascii_bin'
          AND is_nullable='NO')=5
   AND (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='auth_authorization_execution_binding'
          AND ((column_name='actor_id' AND data_type='bigint' AND is_nullable='NO')
            OR (column_name='caller' AND data_type='varchar' AND character_maximum_length=96
                AND collation_name='ascii_bin' AND is_nullable='NO')
            OR (column_name IN ('run_id','session_id') AND data_type='varchar' AND character_maximum_length=128
                AND collation_name='utf8mb4_bin' AND is_nullable='NO')
            OR (column_name IN ('expires_at','hard_deadline') AND data_type='bigint' AND is_nullable='NO')
            OR (column_name='created_at' AND data_type='datetime' AND datetime_precision=6 AND is_nullable='NO')))=7
   AND (SELECT COUNT(*) FROM information_schema.table_constraints
        WHERE constraint_schema=DATABASE() AND table_name='auth_authorization_execution_binding'
          AND constraint_type='CHECK')=10
   AND EXISTS (SELECT 1 FROM information_schema.statistics
               WHERE table_schema=DATABASE() AND table_name='auth_authorization_execution_binding'
                 AND index_name='PRIMARY' AND non_unique=0 AND column_name='ingress_request_hash');
