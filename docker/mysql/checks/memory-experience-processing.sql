SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='bk_memory' AND engine='InnoDB'
        AND table_name IN ('memory_experience_event','memory_experience_selection'))=2
   AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_memory'
        AND ((table_name='memory_experience_event' AND column_name IN ('context_id','owner_id','domain_id','agent_id','evidence_id','experience_id','expected_revision','event_kind','source_caller','request_hash','request_json','source_context_id','source_kind','source_id','source_event_id','source_revision','source_hash','source_schema_version','source_state','occurred_at','accepted_at','updated_at'))
          OR (table_name='memory_experience_selection' AND column_name IN ('context_id','matter_id','matter_revision','objective_hash','scoring_version','token_budget','selection_json','updated_at'))))=30
   AND (SELECT COUNT(DISTINCT CONCAT(table_name,':',index_name)) FROM information_schema.statistics WHERE table_schema='bk_memory'
        AND ((table_name='memory_experience_event' AND index_name IN ('PRIMARY','uk_memory_experience_event_identity','idx_memory_experience_event_source','idx_memory_experience_event_experience'))
          OR (table_name='memory_experience_selection' AND index_name='PRIMARY')))=5;
