SELECT IF(
    NOT EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = 'bk_promotion')
    AND (SELECT COUNT(*) = 1 AND COALESCE(SUM(active <> 0), 0) = 0
         FROM bk_auth.auth_permission
         WHERE BINARY permission_code = 'compare.report.share.public')
    AND (SELECT COUNT(*) = 0 FROM bk_auth.auth_role_permission
         WHERE BINARY permission_code = 'compare.report.share.public')
    AND (SELECT COUNT(*) = 1 FROM bk_auth.auth_authorization_migration
         WHERE migration_id = '20261029_legacy_compare_promotion_retirement'),
    1, 0
);
