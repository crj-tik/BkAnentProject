SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='bk_auth'
        AND table_name='auth_account_logout_event' AND engine='InnoDB')=1
   AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_auth'
        AND table_name='auth_account_logout_event')=7
   AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_auth'
        AND table_name='auth_account_logout_event' AND column_name='token_digest'
        AND data_type='char' AND character_maximum_length=64 AND collation_name='ascii_bin'
        AND is_nullable='NO')=1
   AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_auth'
        AND table_name='auth_account_logout_event' AND column_name='user_id'
        AND data_type='bigint' AND is_nullable='NO')=1
   AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_auth'
        AND table_name='auth_account_logout_event' AND column_name='event_id'
        AND data_type='varchar' AND character_maximum_length=64 AND collation_name='ascii_bin'
        AND is_nullable='NO')=1
   AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_auth'
        AND table_name='auth_account_logout_event' AND column_name='state'
        AND data_type='varchar' AND character_maximum_length=16 AND collation_name='ascii_bin'
        AND is_nullable='NO' AND column_default='PENDING')=1
   AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_auth'
        AND table_name='auth_account_logout_event' AND column_name IN ('expires_at','created_at','updated_at')
        AND data_type='datetime' AND datetime_precision=6 AND is_nullable='NO')=3
   AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='bk_auth'
        AND table_name='auth_account_logout_event' AND index_name='PRIMARY')=1
   AND EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema='bk_auth'
        AND table_name='auth_account_logout_event' AND index_name='PRIMARY'
        AND column_name='token_digest' AND seq_in_index=1 AND non_unique=0)
   AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='bk_auth'
        AND table_name='auth_account_logout_event' AND index_name='idx_auth_logout_pending'
        AND ((seq_in_index=1 AND column_name='state') OR (seq_in_index=2 AND column_name='expires_at')))=2
   AND EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema='bk_auth'
        AND table_name='auth_account_logout_event' AND index_name='idx_auth_logout_user'
        AND column_name='user_id' AND seq_in_index=1)
   AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_auth'
        AND table_name='auth_user_authorization_state' AND column_name IN ('permission_revision','credential_epoch')
        AND data_type='bigint' AND is_nullable='NO')=2
   AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_auth'
        AND table_name='auth_authorization_outbox' AND column_name IN ('event_id','status','lease_epoch'))=3;
