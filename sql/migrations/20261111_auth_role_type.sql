-- Existing roles remain unclassified until a reviewed operator assigns their category.
USE bk_auth;

SELECT COUNT(*) INTO @role_type_exists FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'auth_role' AND column_name = 'role_type';
SET @role_type_sql = IF(@role_type_exists = 0,
    'ALTER TABLE auth_role ADD COLUMN role_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT ''UNCLASSIFIED''',
    'SELECT 1');
PREPARE role_type_stmt FROM @role_type_sql;
EXECUTE role_type_stmt;
DEALLOCATE PREPARE role_type_stmt;
