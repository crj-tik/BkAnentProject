USE bk_platform_operations;

SET @platform_operations_app_sql = CONCAT(
    'ALTER USER ''platform_operations_app''@''%'' IDENTIFIED BY ',
    QUOTE(@platform_operations_app_password),
    ' ACCOUNT UNLOCK'
);
PREPARE provision_platform_operations_app FROM @platform_operations_app_sql;
EXECUTE provision_platform_operations_app;
DEALLOCATE PREPARE provision_platform_operations_app;

SET @platform_operations_source_sql = CONCAT(
    'ALTER USER ''platform_operations_source_ro''@''%'' IDENTIFIED BY ',
    QUOTE(@platform_operations_source_password),
    ' ACCOUNT UNLOCK'
);
PREPARE provision_platform_operations_source FROM @platform_operations_source_sql;
EXECUTE provision_platform_operations_source;
DEALLOCATE PREPARE provision_platform_operations_source;
