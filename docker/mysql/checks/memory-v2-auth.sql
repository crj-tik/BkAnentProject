SELECT (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema='bk_memory' AND table_name='memory_v2_request_nonce'
          AND column_name IN ('key_id','nonce','expires_at','created_at'))=4
   AND (SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema='bk_memory' AND table_name='memory_v2_request_nonce' AND engine='InnoDB')=1
   AND (SELECT COUNT(DISTINCT column_name) FROM information_schema.statistics
        WHERE table_schema='bk_memory' AND table_name='memory_v2_request_nonce' AND index_name='PRIMARY')=2
   AND EXISTS (SELECT 1 FROM information_schema.statistics
               WHERE table_schema='bk_memory' AND table_name='memory_v2_request_nonce'
                 AND index_name='idx_memory_v2_nonce_expiry' AND column_name='expires_at');
