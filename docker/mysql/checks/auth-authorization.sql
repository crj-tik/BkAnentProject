SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='bk_auth' AND engine='InnoDB'
        AND table_name IN ('user_account','auth_role','auth_permission','auth_role_permission','auth_user_role',
          'auth_user_authorization_state','auth_authorization_outbox','auth_authorization_nonce',
          'auth_authorization_grant_boundary','auth_authorization_audit','auth_authorization_writer_lock',
          'auth_authorization_migration'))=12
   AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_auth' AND table_name='user_account'
        AND column_name='business_admin' AND data_type='tinyint' AND is_nullable='NO' AND column_default='0')=1
   AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_auth' AND table_name='auth_role'
        AND column_name IN ('role_code','active','assignable','deleted'))=4
   AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_auth' AND table_name='auth_permission'
        AND column_name IN ('permission_code','domain','operation','active','version'))=5
   AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_auth' AND table_name='auth_permission'
        AND column_name IN ('permission_code','domain','operation') AND collation_name='ascii_bin')=3
   AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_auth' AND table_name='auth_role_permission'
        AND column_name IN ('role_id','permission_code'))=2
   AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_auth' AND table_name='auth_user_role'
        AND column_name IN ('user_id','role_id','valid_from','valid_until','active'))=5
   AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_auth' AND table_name='auth_user_role'
        AND column_name IN ('valid_from','valid_until') AND data_type='datetime' AND datetime_precision=6)=2
   AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_auth' AND table_name='auth_user_authorization_state'
        AND column_name IN ('user_id','permission_revision','credential_epoch','status','updated_at'))=5
   AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_auth' AND table_name='auth_user_authorization_state'
        AND column_name IN ('permission_revision','credential_epoch') AND data_type='bigint' AND column_default='1')=2
   AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_auth' AND table_name='auth_authorization_outbox'
        AND column_name IN ('event_id','affected_users','revisions','reason','committed_at','status','attempts',
          'lease_epoch','lease_until','next_attempt_at'))=10
   AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='bk_auth' AND table_name='auth_authorization_outbox'
        AND index_name='idx_authz_outbox_claim' AND ((seq_in_index=1 AND column_name='status')
          OR (seq_in_index=2 AND column_name='next_attempt_at') OR (seq_in_index=3 AND column_name='lease_until')
          OR (seq_in_index=4 AND column_name='committed_at')))=4
   AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_auth' AND table_name='auth_authorization_nonce'
        AND column_name IN ('caller','key_id','nonce_hash','expires_at'))=4
   AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='bk_auth' AND table_name='auth_authorization_nonce'
        AND index_name='PRIMARY' AND non_unique=0 AND ((seq_in_index=1 AND column_name='caller')
          OR (seq_in_index=2 AND column_name='key_id') OR (seq_in_index=3 AND column_name='nonce_hash')))=3
   AND EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema='bk_auth' AND table_name='auth_authorization_nonce'
        AND index_name='idx_authz_nonce_expiry' AND column_name='expires_at')
   AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_auth' AND table_name='auth_authorization_grant_boundary'
        AND column_name IN ('user_id','role_id','boundary_kind','boundary_at','processed_at'))=5
   AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_auth' AND table_name='auth_authorization_audit'
        AND column_name IN ('audit_id','actor_id','actor_revision','action','target_type','target_id','event_id','created_at'))=8
   AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='bk_auth' AND table_name='auth_role_permission'
        AND index_name='PRIMARY' AND non_unique=0 AND column_name IN ('role_id','permission_code'))=2
   AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='bk_auth' AND table_name='auth_user_role'
        AND index_name='PRIMARY' AND non_unique=0 AND column_name IN ('user_id','role_id'))=2
   AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='bk_auth' AND table_name='auth_authorization_grant_boundary'
        AND index_name='PRIMARY' AND non_unique=0 AND column_name IN ('user_id','role_id','boundary_kind','boundary_at'))=4
   AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_auth' AND table_name='auth_authorization_writer_lock'
        AND column_name='lock_id')=1
   AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_auth' AND table_name='auth_authorization_migration'
        AND column_name IN ('migration_id','applied_at'))=2;
