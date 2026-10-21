SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='bk_memory' AND engine='InnoDB'
        AND table_name IN ('memory_experience','memory_experience_revision','memory_experience_evidence','memory_experience_evidence_source'))=4
   AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_memory'
        AND ((table_name='memory_experience' AND column_name IN ('owner_id','domain_id','agent_id','experience_id','revision','content_hash','schema_version','lifecycle_revision','state','confidence','activation','last_observed_at','last_validated_at','retention_until','created_at','updated_at'))
          OR (table_name='memory_experience_revision' AND column_name IN ('owner_id','domain_id','agent_id','experience_id','revision','content_hash','schema_version','payload_json','created_at'))
          OR (table_name='memory_experience_evidence' AND column_name IN ('owner_id','domain_id','agent_id','experience_id','experience_revision','evidence_id','evidence_kind','outcome','feedback','verified','occurred_at','observed_at','state'))
          OR (table_name='memory_experience_evidence_source' AND column_name IN ('source_link_hash','owner_id','domain_id','agent_id','experience_id','experience_revision','evidence_id','source_context_id','source_kind','source_id','source_event_id','source_sequence','source_revision','source_hash','source_schema_version','grant_id','grant_revision','state','created_at'))))=57
   AND (SELECT COUNT(DISTINCT CONCAT(table_name,':',index_name)) FROM information_schema.statistics WHERE table_schema='bk_memory'
        AND ((table_name='memory_experience' AND index_name IN ('PRIMARY','idx_memory_experience_retention'))
          OR (table_name='memory_experience_revision' AND index_name IN ('PRIMARY','idx_memory_experience_revision_hash'))
          OR (table_name='memory_experience_evidence' AND index_name IN ('PRIMARY','idx_memory_experience_evidence_version'))
          OR (table_name='memory_experience_evidence_source' AND index_name IN ('PRIMARY','uk_memory_experience_source','idx_memory_experience_source_target','idx_memory_experience_source_event'))))=10;
