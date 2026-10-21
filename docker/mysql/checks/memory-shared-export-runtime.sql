SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='bk_memory' AND engine='InnoDB'
        AND table_name IN ('memory_shared_export_lock','memory_shared_export_runtime','memory_shared_export_projection'))=3
   AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_memory'
        AND ((table_name='memory_shared_export_lock' AND column_name='lock_id')
          OR (table_name='memory_shared_export_runtime' AND column_name IN ('export_id','content_revision','source_invocation_id','submit_request_hash','index_state','last_error','updated_at'))
          OR (table_name='memory_shared_export_projection' AND column_name IN ('export_id','content_revision','recipient_domain_id','recipient_agent_id','recipient_conversation_id','grant_id','space_id','document_id','event_id','index_state'))))=18
   AND (SELECT COUNT(DISTINCT CONCAT(table_name,':',index_name)) FROM information_schema.statistics WHERE table_schema='bk_memory'
        AND ((table_name='memory_shared_export_lock' AND index_name='PRIMARY')
          OR (table_name='memory_shared_export_runtime' AND index_name IN ('PRIMARY','uk_memory_export_invocation'))
          OR (table_name='memory_shared_export_projection' AND index_name IN ('PRIMARY','uk_memory_export_projection_event','idx_memory_export_projection_scope'))))=6
   AND (SELECT COUNT(*) FROM bk_memory.memory_shared_export_lock WHERE lock_id=1)=1;
