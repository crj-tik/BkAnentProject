SELECT IF(
    (SELECT COUNT(*) = 2
            AND COALESCE(SUM(active <> 0), 0) = 0
            AND COALESCE(SUM(
                (permission_code = 'compare.report.create' AND domain = 'listing'
                    AND operation = 'comparison.report.create')
                OR
                (permission_code = 'compare.report.read.own' AND domain = 'listing'
                    AND operation = 'comparison.report.read.own')
            ), 0) = 2
     FROM bk_auth.auth_permission
     WHERE permission_code IN ('compare.report.create', 'compare.report.read.own'))
    AND (SELECT COUNT(*) = 0 FROM bk_auth.auth_role_permission
         WHERE permission_code IN ('compare.report.create', 'compare.report.read.own'))
    AND (SELECT COUNT(*) = 1 FROM bk_auth.auth_authorization_migration
         WHERE migration_id = '20261028_baseline_permission_retirement')
    AND (SELECT COUNT(*) = 1 FROM bk_auth.auth_authorization_migration
         WHERE migration_id = '20261028_listing_comparison_authorization_domain'),
    1, 0
);
