SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='bk_memory' AND engine='InnoDB'
        AND table_name IN ('memory_conversation','memory_message','memory_receive_permit','memory_receive_event',
                           'memory_result_admission','memory_summary','memory_entity_anchor','memory_matter',
                           'memory_source_dependency','memory_processing_job'))=10
   AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_memory'
        AND ((table_name='memory_conversation' AND column_name IN ('context_id','owner_id','domain_id','agent_id','conversation_id','revision','last_sequence','covered_through','policy_version'))
          OR (table_name='memory_message' AND column_name IN ('context_id','sequence_no','message_id','source_event_id','content_hash','content_revision','schema_version','source_caller','state','payload_json'))
          OR (table_name='memory_receive_permit' AND column_name IN ('permit_id','context_id','source_caller','request_id','body_hash','expires_at'))
          OR (table_name='memory_receive_event' AND column_name IN ('permit_id','event_id','content_hash','purpose','admission_json','receipt_json'))
          OR (table_name='memory_result_admission' AND column_name IN ('admission_key','admission_hash','context_id','result_event_id'))
          OR (table_name='memory_summary' AND column_name IN ('context_id','summary_id','content_revision','content_hash','covered_from','covered_through','state','payload_json','receipt_json'))
          OR (table_name='memory_entity_anchor' AND column_name IN ('context_id','entity_id','content_revision','summary_id','summary_revision','state','payload_json'))
          OR (table_name='memory_matter' AND column_name IN ('context_id','matter_id','content_revision','state','payload_json'))
          OR (table_name='memory_source_dependency' AND column_name IN ('target_context_id','target_kind','target_id','target_revision','source_context_id','source_event_id','dependency_json'))
          OR (table_name='memory_processing_job' AND column_name IN ('job_id','context_id','source_event_id','job_kind','state','attempts','available_at','lease_owner','lease_until','last_error'))))=73
   AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='bk_memory' AND table_name='memory_message'
        AND index_name IN ('uk_memory_message_event','uk_memory_message_id') AND seq_in_index=1 AND non_unique=0)=2;
