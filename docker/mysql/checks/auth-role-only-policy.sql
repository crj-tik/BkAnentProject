SELECT
    (SELECT COUNT(*) FROM information_schema.tables
     WHERE table_schema='bk_auth' AND table_name='auth_authorization_policy') = 1
    AND (SELECT COUNT(*) FROM bk_auth.auth_authorization_policy WHERE policy_id=1 AND role_only_mode IN (0,1)) = 1
    AND (SELECT COUNT(*) FROM information_schema.columns
         WHERE table_schema='bk_auth' AND table_name='auth_authorization_policy'
           AND column_name IN ('policy_id','role_only_mode','updated_at')) = 3;
