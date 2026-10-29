SELECT IF(
    (SELECT COUNT(*) = 2 FROM information_schema.columns
     WHERE table_schema = 'bk_auth' AND table_name = 'auth_user_store_identity'
       AND column_name IN ('user_id', 'store_id') AND is_nullable = 'NO')
    AND (SELECT COUNT(*) = 1 FROM information_schema.statistics
         WHERE table_schema = 'bk_auth' AND table_name = 'auth_user_store_identity'
           AND index_name = 'PRIMARY' AND column_name = 'user_id' AND non_unique = 0)
    AND (SELECT COUNT(*) = 1 FROM information_schema.statistics
         WHERE table_schema = 'bk_auth' AND table_name = 'auth_user_store_identity'
           AND index_name = 'idx_auth_store_identity_store_user' AND column_name = 'store_id'
           AND seq_in_index = 1 AND non_unique = 1),
    1, 0
);
