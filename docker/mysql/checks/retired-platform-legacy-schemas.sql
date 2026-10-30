SELECT IF(
    NOT EXISTS (SELECT 1 FROM information_schema.schemata
                WHERE schema_name IN ('bk_business', 'bk_business_review', 'bk_knowledge'))
    AND EXISTS (SELECT 1 FROM bk_auth.auth_authorization_migration
                WHERE migration_id = '20261030_retire_platform_legacy_schemas')
    AND NOT EXISTS (SELECT 1 FROM bk_auth.retired_schema_migration
                    WHERE schema_name IN ('bk_business', 'bk_business_review', 'bk_knowledge')
                      AND retirement_state <> 'retired')
    AND NOT EXISTS (SELECT 1 FROM mysql.user
                    WHERE (User = 'knowledge_app' AND Host = '%')
                       OR (User = 'knowledge_source_ro' AND Host = '%')
                       OR (User = 'knowledge_source_view_owner' AND Host = '%')),
    1, 0
);
