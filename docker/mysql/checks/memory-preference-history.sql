SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='bk_memory' AND engine='InnoDB'
        AND table_name IN ('memory_message_observation','memory_preference_block','memory_preference_revision','memory_preference_transition'))=4
   AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_memory' AND table_name='memory_preference_transition'
        AND column_name IN ('block_id','transition_id','history_ordinal','introduced_revision','source_context_id','source_event_id','candidate_hash','transition_hash','source_json','payload_json'))=10
   AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='bk_memory' AND table_name='memory_preference_transition'
        AND index_name='uk_memory_preference_evidence' AND non_unique=0)=3;
