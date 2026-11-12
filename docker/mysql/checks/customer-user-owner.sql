SELECT
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_customer'
       AND ((table_name='customer_profile' AND column_name='owner_user_id')
         OR (table_name='customer_follow_record' AND column_name='owner_user_id')
         OR (table_name='customer_listing_inquiry' AND column_name='owner_user_id'))) = 3
    AND (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema='bk_customer'
       AND index_name IN ('idx_customer_profile_owner_user','idx_customer_follow_owner_user',
                          'idx_customer_inquiry_owner_user')) = 3
    AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='bk_customer'
       AND table_name='customer_owner_migration_ledger' AND engine='InnoDB') = 1
    AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_customer'
       AND table_name='customer_owner_migration_ledger'
       AND column_name IN ('record_type','record_id','legacy_broker_id','candidate_user_id',
                           'evidence_reference','status','verified_by_user_id','verified_at','applied_at')) = 9;
