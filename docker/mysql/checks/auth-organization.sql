SELECT
    (SELECT COUNT(*) FROM information_schema.tables
     WHERE table_schema = 'bk_auth' AND table_name IN ('auth_org_unit', 'auth_org_membership')) = 2
    AND (SELECT COUNT(*) FROM information_schema.columns
         WHERE table_schema = 'bk_auth' AND table_name = 'auth_org_unit'
           AND column_name IN ('org_code', 'org_type', 'parent_id', 'store_business_id', 'status', 'revision')) = 6
    AND (SELECT COUNT(*) FROM information_schema.columns
         WHERE table_schema = 'bk_auth' AND table_name = 'auth_org_membership'
           AND column_name IN ('user_id', 'org_id', 'is_primary', 'status', 'valid_from', 'valid_until')) = 6
    AND (SELECT COUNT(DISTINCT constraint_name) FROM information_schema.table_constraints
         WHERE constraint_schema = 'bk_auth' AND constraint_name IN
           ('fk_auth_org_parent', 'fk_auth_org_member_user', 'fk_auth_org_member_org')) = 3;
