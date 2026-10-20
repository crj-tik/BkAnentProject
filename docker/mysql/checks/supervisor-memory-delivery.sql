SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='bk_agent' AND engine='InnoDB'
        AND table_name IN ('agent_memory_cursor','agent_memory_delivery'))=2
   AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_agent'
        AND ((table_name='agent_memory_cursor' AND column_name IN ('context_id','writer_fence','last_sequence'))
          OR (table_name='agent_memory_delivery' AND column_name IN ('context_id','event_id','run_id','checkpoint_version',
              'message_ordinal','sequence_no','content_hash','permit_id','state','last_error'))))=13
   AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='bk_agent'
        AND table_name='agent_memory_delivery'
        AND index_name IN ('PRIMARY','uk_agent_memory_sequence','idx_agent_memory_pending')
        AND seq_in_index=1
        AND ((index_name='idx_agent_memory_pending' AND non_unique=1)
          OR (index_name IN ('PRIMARY','uk_agent_memory_sequence') AND non_unique=0)))=3;
