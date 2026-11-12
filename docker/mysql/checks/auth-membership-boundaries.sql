SELECT
    (SELECT COUNT(*) FROM information_schema.tables
     WHERE table_schema='bk_auth' AND table_name='auth_authorization_membership_boundary') = 1
    AND (SELECT COUNT(*) FROM information_schema.columns
         WHERE table_schema='bk_auth' AND table_name='auth_authorization_membership_boundary'
           AND column_name IN ('membership_id','boundary_kind','boundary_at','processed_at')) = 4
    AND (SELECT COUNT(*) FROM information_schema.table_constraints
         WHERE constraint_schema='bk_auth' AND table_name='auth_authorization_membership_boundary'
           AND constraint_name='fk_auth_org_boundary_membership') = 1;
