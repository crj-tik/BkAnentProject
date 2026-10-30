-- Retire only the reviewed, unused contract/settlement development schemas.
-- Preserve their migration checksums before MySQL's implicitly committing DROP DATABASE.
USE bk_auth;
CREATE TABLE IF NOT EXISTS retired_schema_migration (
    schema_name VARCHAR(64) NOT NULL,
    migration_id VARCHAR(128) NOT NULL,
    file_sha256 CHAR(64) NOT NULL,
    check_sha256 CHAR(64) NOT NULL,
    retirement_state VARCHAR(16) NOT NULL,
    PRIMARY KEY (schema_name, migration_id)
);

DROP PROCEDURE IF EXISTS retire_contract_settlement_databases;
DELIMITER $$
CREATE PROCEDURE retire_contract_settlement_databases()
BEGIN
    DECLARE done BOOLEAN DEFAULT FALSE;
    DECLARE target_schema VARCHAR(64);
    DECLARE target_table VARCHAR(64);
    DECLARE total_business_rows BIGINT UNSIGNED DEFAULT 0;
    DECLARE tables_cursor CURSOR FOR
        SELECT table_schema, table_name FROM information_schema.tables
        WHERE table_schema IN ('bk_contract', 'bk_settlement') AND table_type='BASE TABLE'
          AND table_name <> 'bk_schema_migration'
          AND NOT (table_schema='bk_contract' AND table_name='contract_template')
          AND NOT (table_schema='bk_settlement' AND table_name IN ('settlement_rule','settlement_rule_tier'));
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = TRUE;

    OPEN tables_cursor;
    scan_tables: LOOP
        FETCH tables_cursor INTO target_schema, target_table;
        IF done THEN LEAVE scan_tables; END IF;
        SET @retire_count = 0;
        SET @retire_sql = CONCAT('SELECT COUNT(*) INTO @retire_count FROM `',
            REPLACE(target_schema, '`', '``'), '`.`', REPLACE(target_table, '`', '``'), '`');
        PREPARE retire_count_statement FROM @retire_sql;
        EXECUTE retire_count_statement;
        DEALLOCATE PREPARE retire_count_statement;
        SET total_business_rows = total_business_rows + COALESCE(@retire_count, 0);
    END LOOP;
    CLOSE tables_cursor;
    IF total_business_rows <> 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Contract or settlement contains business records; retirement refused';
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name='bk_contract') THEN
        IF (SELECT COUNT(*) FROM bk_contract.bk_schema_migration) <> 1 OR
           (SELECT COUNT(*) FROM bk_contract.contract_template) <> 1 OR
           (SELECT COUNT(*) FROM bk_contract.contract_template WHERE template_code='SALE_DEFAULT_V1') <> 1 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Contract template differs from reviewed initialization';
        END IF;
        INSERT IGNORE INTO bk_auth.retired_schema_migration
            (schema_name, migration_id, file_sha256, check_sha256, retirement_state)
            SELECT 'bk_contract', migration_id, file_sha256, check_sha256, 'prepared'
            FROM bk_contract.bk_schema_migration WHERE migration_id='a2a-task-authorization-contract-20261026-v1';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name='bk_settlement') THEN
        IF (SELECT COUNT(*) FROM bk_settlement.bk_schema_migration) <> 1 OR
           (SELECT COUNT(*) FROM bk_settlement.settlement_rule) <> 2 OR
           (SELECT COUNT(*) FROM bk_settlement.settlement_rule WHERE rule_code IN ('SALE_DEFAULT','LEASE_DEFAULT')) <> 2 OR
           (SELECT COUNT(*) FROM bk_settlement.settlement_rule_tier) <> 1 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Settlement rules differ from reviewed initialization';
        END IF;
        INSERT IGNORE INTO bk_auth.retired_schema_migration
            (schema_name, migration_id, file_sha256, check_sha256, retirement_state)
            SELECT 'bk_settlement', migration_id, file_sha256, check_sha256, 'prepared'
            FROM bk_settlement.bk_schema_migration WHERE migration_id='a2a-task-authorization-settlement-20261026-v1';
    END IF;
    IF (SELECT COUNT(*) FROM bk_auth.retired_schema_migration
        WHERE (schema_name='bk_contract' AND migration_id='a2a-task-authorization-contract-20261026-v1'
               OR schema_name='bk_settlement' AND migration_id='a2a-task-authorization-settlement-20261026-v1')
          AND file_sha256='8946aa62546215fc1c6781dc84cf03cda8ee2ba0ccc6ec5ca321a49cfb5a411c'
          AND check_sha256='82a67a4233cddd0779117024917fd9efce6002174e1ebe6522dbaa22852d0c84'
          AND retirement_state IN ('prepared','retired')) <> 2 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Retired schema migration ledger is incomplete or changed';
    END IF;

    DROP DATABASE IF EXISTS bk_contract;
    DROP DATABASE IF EXISTS bk_settlement;
    UPDATE bk_auth.retired_schema_migration SET retirement_state='retired'
      WHERE (schema_name='bk_contract' AND migration_id='a2a-task-authorization-contract-20261026-v1')
         OR (schema_name='bk_settlement' AND migration_id='a2a-task-authorization-settlement-20261026-v1');
END$$
DELIMITER ;
CALL retire_contract_settlement_databases();
DROP PROCEDURE retire_contract_settlement_databases;
