SELECT
    (SELECT COUNT(*) FROM information_schema.tables
     WHERE table_schema='bk_auth' AND table_name='auth_user_business_identity')=1
    AND (SELECT COUNT(*) FROM information_schema.columns
         WHERE table_schema='bk_auth' AND table_name='auth_user_business_identity'
           AND column_name IN ('user_id','identity_type','identity_id'))=3
    AND (SELECT COUNT(*) FROM information_schema.statistics
         WHERE table_schema='bk_auth' AND table_name='auth_user_business_identity'
           AND index_name='uk_auth_business_identity_type_id' AND non_unique=0)=1
    AND (SELECT COUNT(*) FROM bk_auth.auth_permission
         WHERE permission_code='auth.business-identity.manage' AND domain='auth'
           AND operation='business-identity.manage' AND active=1)=1
    AND (SELECT COUNT(*) FROM bk_auth.auth_authorization_migration
         WHERE migration_id='20261026_auth_business_identity_mapping')=1;
