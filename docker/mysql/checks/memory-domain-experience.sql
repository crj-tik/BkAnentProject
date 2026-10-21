SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='bk_memory' AND engine='InnoDB'
        AND table_name IN ('memory_domain_experience','memory_domain_experience_revision','memory_domain_experience_event'))=3
   AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_memory'
        AND ((table_name='memory_domain_experience' AND column_name IN ('domain_id','agent_id','aggregate_id','revision','state','capability_tag','content_hash','payload_json','governance_approval_id','policy_version','source_review_ref','purpose','independent_non_personal','retention_authorized','valid_until','updated_at'))
          OR (table_name='memory_domain_experience_revision' AND column_name IN ('domain_id','agent_id','aggregate_id','revision','state','content_hash','governance_approval_id','policy_version','source_review_ref','purpose','independent_non_personal','retention_authorized','payload_json','withdrawal_reason','changed_at'))
          OR (table_name='memory_domain_experience_event' AND column_name IN ('caller_id','request_id','request_hash','domain_id','agent_id','aggregate_id','revision','state','response_json','created_at'))))=41
   AND (SELECT COUNT(DISTINCT CONCAT(table_name,':',index_name)) FROM information_schema.statistics WHERE table_schema='bk_memory'
        AND ((table_name='memory_domain_experience' AND index_name IN ('PRIMARY','idx_memory_domain_experience_search','idx_memory_domain_experience_capability'))
          OR (table_name='memory_domain_experience_revision' AND index_name='PRIMARY')
          OR (table_name='memory_domain_experience_event' AND index_name IN ('PRIMARY','idx_memory_domain_experience_event_target'))))=6;
