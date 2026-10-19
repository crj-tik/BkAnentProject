SELECT (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_memory' AND
       ((table_name='memory_share_grant' AND column_name IN ('grant_id','grant_revision','owner_id','source_domain_id','source_agent_id','source_conversation_id','scope_kind','shared_scope_id','matter_id','purpose','state','valid_from','valid_until','grant_payload','updated_at'))
        OR (table_name='memory_share_grant_history' AND column_name IN ('grant_id','grant_revision','grant_payload','created_at'))
        OR (table_name='memory_shared_export' AND column_name IN ('export_id','content_revision','content_hash','schema_version','owner_id','source_domain_id','source_agent_id','source_conversation_id','grant_id','grant_revision','matter_id','purpose','state','lifecycle_revision','payload_json','created_at'))))=35
   AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='bk_memory'
        AND table_name IN ('memory_share_grant','memory_share_grant_history','memory_shared_export') AND engine='InnoDB')=3
   AND (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema='bk_memory'
        AND table_name='memory_shared_export' AND index_name IN ('PRIMARY','idx_memory_export_grant','idx_memory_export_source'))=3;
