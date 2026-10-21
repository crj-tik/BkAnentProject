SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='bk_memory' AND engine='InnoDB'
        AND table_name='memory_rag_outbox')=1
   AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_memory' AND table_name='memory_rag_outbox'
        AND column_name IN ('state','next_attempt_at','lease_owner','lease_until'))=4
   AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='bk_memory' AND table_name='memory_rag_outbox'
        AND index_name='idx_memory_rag_dispatch' AND ((seq_in_index=1 AND column_name='state')
          OR (seq_in_index=2 AND column_name='next_attempt_at') OR (seq_in_index=3 AND column_name='lease_until')))=3;
