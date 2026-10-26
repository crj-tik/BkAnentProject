SELECT (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema='bk_marketing' AND table_name='marketing_content'
          AND column_name='owner_broker_id' AND is_nullable='YES')=1
   AND (SELECT COUNT(*) FROM information_schema.statistics
        WHERE table_schema='bk_marketing' AND table_name='marketing_content'
          AND index_name='idx_marketing_content_owner_listing')>0;
