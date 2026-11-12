SELECT
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_marketing'
       AND table_name='marketing_content' AND column_name='owner_user_id') = 1
    AND (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema='bk_marketing'
       AND table_name='marketing_content' AND index_name='idx_marketing_content_owner_user') = 1
    AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='bk_marketing'
       AND table_name='marketing_owner_migration_ledger' AND engine='InnoDB') = 1
    AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_marketing'
       AND table_name='marketing_owner_migration_ledger'
       AND column_name IN ('record_type','record_id','legacy_broker_id','candidate_user_id',
                           'evidence_reference','status','verified_by_user_id','verified_at','applied_at')) = 9;
