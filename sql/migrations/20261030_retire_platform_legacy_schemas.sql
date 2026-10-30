-- Retire the pre-consolidation Business, Business Review, and Knowledge schemas.
-- Business tables must be empty; migration ledgers are preserved centrally before DROP DATABASE.
USE bk_auth;

CREATE TABLE IF NOT EXISTS retired_schema_migration (
    schema_name VARCHAR(64) NOT NULL,
    migration_id VARCHAR(128) NOT NULL,
    file_sha256 CHAR(64) NOT NULL,
    check_sha256 CHAR(64) NOT NULL,
    retirement_state VARCHAR(16) NOT NULL,
    PRIMARY KEY (schema_name, migration_id)
);

DROP PROCEDURE IF EXISTS retire_platform_legacy_schemas;
DELIMITER $$
CREATE PROCEDURE retire_platform_legacy_schemas()
BEGIN
    DECLARE tables_finished BOOLEAN DEFAULT FALSE;
    DECLARE target_schema VARCHAR(64);
    DECLARE target_table VARCHAR(64);
    DECLARE table_rows BIGINT UNSIGNED DEFAULT 0;
    DECLARE total_business_rows BIGINT UNSIGNED DEFAULT 0;
    DECLARE tables_cursor CURSOR FOR
        SELECT table_schema, table_name
        FROM information_schema.tables
        WHERE table_schema IN ('bk_business', 'bk_business_review', 'bk_knowledge')
          AND table_type = 'BASE TABLE'
          AND table_name <> 'bk_schema_migration';
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET tables_finished = TRUE;

    IF EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = 'bk_business')
       AND NOT EXISTS (SELECT 1 FROM information_schema.tables
                       WHERE table_schema = 'bk_business' AND table_name = 'bk_schema_migration') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Legacy bk_business has no migration ledger; retirement refused';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = 'bk_business_review')
       AND NOT EXISTS (SELECT 1 FROM information_schema.tables
                       WHERE table_schema = 'bk_business_review' AND table_name = 'bk_schema_migration') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Legacy bk_business_review has no migration ledger; retirement refused';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = 'bk_knowledge')
       AND NOT EXISTS (SELECT 1 FROM information_schema.tables
                       WHERE table_schema = 'bk_knowledge' AND table_name = 'bk_schema_migration') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Legacy bk_knowledge has no migration ledger; retirement refused';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = 'bk_business') THEN
        IF (SELECT COUNT(*) FROM bk_business.bk_schema_migration) = 0 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Legacy bk_business migration ledger is empty; retirement refused';
        END IF;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = 'bk_business_review') THEN
        IF (SELECT COUNT(*) FROM bk_business_review.bk_schema_migration) = 0 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Legacy bk_business_review migration ledger is empty; retirement refused';
        END IF;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = 'bk_knowledge') THEN
        IF (SELECT COUNT(*) FROM bk_knowledge.bk_schema_migration) = 0 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Legacy bk_knowledge migration ledger is empty; retirement refused';
        END IF;
    END IF;

    OPEN tables_cursor;
    read_tables: LOOP
        FETCH tables_cursor INTO target_schema, target_table;
        IF tables_finished THEN
            LEAVE read_tables;
        END IF;
        SET @retire_count = 0;
        SET @retire_sql = CONCAT(
            'SELECT COUNT(*) INTO @retire_count FROM `',
            REPLACE(target_schema, '`', '``'), '`.`', REPLACE(target_table, '`', '``'), '`'
        );
        PREPARE retire_count_statement FROM @retire_sql;
        EXECUTE retire_count_statement;
        DEALLOCATE PREPARE retire_count_statement;
        SET table_rows = COALESCE(@retire_count, 0);
        SET total_business_rows = total_business_rows + table_rows;
    END LOOP;
    CLOSE tables_cursor;

    IF total_business_rows <> 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Legacy platform-operation schemas contain business records; retirement refused';
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = 'bk_business') THEN
        INSERT IGNORE INTO bk_auth.retired_schema_migration
            (schema_name, migration_id, file_sha256, check_sha256, retirement_state)
        SELECT 'bk_business', migration_id, file_sha256, check_sha256, 'prepared'
        FROM bk_business.bk_schema_migration;
        IF EXISTS (
            SELECT 1 FROM bk_business.bk_schema_migration m
            LEFT JOIN bk_auth.retired_schema_migration r
              ON r.schema_name = 'bk_business' AND r.migration_id = m.migration_id
            WHERE r.migration_id IS NULL OR r.file_sha256 <> m.file_sha256
               OR r.check_sha256 <> m.check_sha256 OR r.retirement_state <> 'prepared'
        ) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Legacy bk_business migration ledger differs from retirement ledger';
        END IF;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = 'bk_business_review') THEN
        INSERT IGNORE INTO bk_auth.retired_schema_migration
            (schema_name, migration_id, file_sha256, check_sha256, retirement_state)
        SELECT 'bk_business_review', migration_id, file_sha256, check_sha256, 'prepared'
        FROM bk_business_review.bk_schema_migration;
        IF EXISTS (
            SELECT 1 FROM bk_business_review.bk_schema_migration m
            LEFT JOIN bk_auth.retired_schema_migration r
              ON r.schema_name = 'bk_business_review' AND r.migration_id = m.migration_id
            WHERE r.migration_id IS NULL OR r.file_sha256 <> m.file_sha256
               OR r.check_sha256 <> m.check_sha256 OR r.retirement_state <> 'prepared'
        ) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Legacy bk_business_review migration ledger differs from retirement ledger';
        END IF;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = 'bk_knowledge') THEN
        INSERT IGNORE INTO bk_auth.retired_schema_migration
            (schema_name, migration_id, file_sha256, check_sha256, retirement_state)
        SELECT 'bk_knowledge', migration_id, file_sha256, check_sha256, 'prepared'
        FROM bk_knowledge.bk_schema_migration;
        IF EXISTS (
            SELECT 1 FROM bk_knowledge.bk_schema_migration m
            LEFT JOIN bk_auth.retired_schema_migration r
              ON r.schema_name = 'bk_knowledge' AND r.migration_id = m.migration_id
            WHERE r.migration_id IS NULL OR r.file_sha256 <> m.file_sha256
               OR r.check_sha256 <> m.check_sha256 OR r.retirement_state <> 'prepared'
        ) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Legacy bk_knowledge migration ledger differs from retirement ledger';
        END IF;
    END IF;

    DROP DATABASE IF EXISTS bk_business;
    DROP DATABASE IF EXISTS bk_business_review;
    DROP DATABASE IF EXISTS bk_knowledge;
    UPDATE bk_auth.retired_schema_migration
    SET retirement_state = 'retired'
    WHERE schema_name IN ('bk_business', 'bk_business_review', 'bk_knowledge')
      AND retirement_state = 'prepared';

    DROP USER IF EXISTS 'knowledge_app'@'%';
    DROP USER IF EXISTS 'knowledge_source_ro'@'%';
    DROP USER IF EXISTS 'knowledge_source_view_owner'@'%';
    INSERT IGNORE INTO bk_auth.auth_authorization_migration (migration_id)
    VALUES ('20261030_retire_platform_legacy_schemas');
END$$
DELIMITER ;

CALL retire_platform_legacy_schemas();
DROP PROCEDURE retire_platform_legacy_schemas;
