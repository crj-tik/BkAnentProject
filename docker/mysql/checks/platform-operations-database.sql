SELECT IF(EXISTS(SELECT 1 FROM information_schema.schemata
                 WHERE schema_name='bk_platform_operations'), 1, 0);
