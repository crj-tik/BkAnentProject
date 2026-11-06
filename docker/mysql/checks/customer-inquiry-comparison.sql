SELECT
 (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='bk_customer' AND table_name='customer_inquiry_comparison' AND engine='InnoDB')=1
 AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_customer' AND table_name='customer_inquiry_comparison' AND column_name IN ('id','inquiry_id','report_id','created_by','created_at'))=5
 AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema='bk_customer' AND constraint_type='PRIMARY KEY' AND table_name='customer_inquiry_comparison')=1
 AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema='bk_customer' AND constraint_type='UNIQUE' AND constraint_name='uk_customer_inquiry_comparison')=1
 AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='bk_customer' AND table_name='customer_inquiry_comparison' AND index_name='uk_customer_inquiry_comparison' AND non_unique=0 AND ((seq_in_index=1 AND column_name='inquiry_id') OR (seq_in_index=2 AND column_name='report_id')))=2
 AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='bk_customer' AND table_name='customer_inquiry_comparison' AND index_name='idx_customer_inquiry_comparison_inquiry' AND ((seq_in_index=1 AND column_name='inquiry_id') OR (seq_in_index=2 AND column_name='created_at')))=2;
