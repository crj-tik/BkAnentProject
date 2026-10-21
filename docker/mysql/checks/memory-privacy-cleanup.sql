SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='bk_memory' AND engine='InnoDB'
        AND table_name IN ('memory_privacy_deletion','memory_privacy_cleanup_target'))=2
   AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_memory'
        AND ((table_name='memory_privacy_deletion' AND column_name IN ('context_id','source_event_id','source_hash','action_kind','cleanup_state','attempts','last_error','requested_at','updated_at','completed_at'))
          OR (table_name='memory_privacy_cleanup_target' AND column_name IN ('context_id','source_event_id','projection_id','cleanup_state','last_error','updated_at'))))=16
   AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='bk_memory'
        AND index_name IN ('idx_memory_privacy_state','idx_memory_privacy_target_state'))=5;
