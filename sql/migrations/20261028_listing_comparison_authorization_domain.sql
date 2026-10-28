-- Reclassify the stable comparison action identifiers under the consolidated Listing domain.
-- The baseline-permission-retirement migration runs first: these catalog rows stay inactive and ungranted.
-- This migration changes catalog metadata only; the signed baseline action identifiers remain stable.
USE bk_auth;

SET @listing_comparison_auth_catalog_complete = (
    SELECT COUNT(*) = 2 FROM auth_permission
    WHERE permission_code IN ('compare.report.create', 'compare.report.read.own')
);

SET @listing_comparison_baseline_retired = EXISTS (
    SELECT 1 FROM auth_authorization_migration
    WHERE migration_id = '20261028_baseline_permission_retirement'
);

SET @listing_comparison_authorization_domain_migration = @listing_comparison_auth_catalog_complete
    AND @listing_comparison_baseline_retired
    AND NOT EXISTS (
    SELECT 1 FROM auth_authorization_migration
    WHERE migration_id = '20261028_listing_comparison_authorization_domain'
);

SET @listing_comparison_auth_catalog_changed = @listing_comparison_authorization_domain_migration
    AND EXISTS (
        SELECT 1 FROM auth_permission
        WHERE (permission_code = 'compare.report.create'
                   AND (domain <> 'listing' OR operation <> 'comparison.report.create'))
           OR (permission_code = 'compare.report.read.own'
                   AND (domain <> 'listing' OR operation <> 'comparison.report.read.own'))
    );

START TRANSACTION;
SELECT lock_id FROM auth_authorization_writer_lock WHERE lock_id = 1 FOR UPDATE;

UPDATE auth_permission
SET domain = 'listing',
    operation = CASE permission_code
        WHEN 'compare.report.create' THEN 'comparison.report.create'
        WHEN 'compare.report.read.own' THEN 'comparison.report.read.own'
    END,
    version = version + 1
WHERE @listing_comparison_authorization_domain_migration
  AND permission_code IN ('compare.report.create', 'compare.report.read.own')
  AND (
      (permission_code = 'compare.report.create'
          AND (domain <> 'listing' OR operation <> 'comparison.report.create'))
      OR
      (permission_code = 'compare.report.read.own'
          AND (domain <> 'listing' OR operation <> 'comparison.report.read.own'))
  );

UPDATE auth_user_authorization_state
SET permission_revision = permission_revision + 1,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE @listing_comparison_auth_catalog_changed;

INSERT INTO auth_authorization_outbox (event_id, affected_users, revisions, reason)
SELECT CONCAT('listing-compare-domain-', s.user_id),
       CONCAT('[', s.user_id, ']'),
       JSON_ARRAY(JSON_OBJECT('userId', s.user_id,
                              'permissionRevision', s.permission_revision,
                              'credentialEpoch', s.credential_epoch,
                              'status', s.status)),
       'CATALOG_CHANGE'
FROM auth_user_authorization_state s
WHERE @listing_comparison_auth_catalog_changed;

INSERT INTO auth_authorization_migration (migration_id)
SELECT '20261028_listing_comparison_authorization_domain'
WHERE @listing_comparison_authorization_domain_migration;
COMMIT;
