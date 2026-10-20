SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='bk_memory' AND engine='InnoDB'
        AND table_name='memory_conversation_writer')=1
   AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_memory'
        AND table_name='memory_conversation_writer'
        AND column_name IN ('context_id','source_caller','run_id','acquire_id','fence','expires_at_ms'))=6
   AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_memory'
        AND table_name='memory_conversation_writer' AND column_name='context_id' AND is_nullable='NO')=1;
