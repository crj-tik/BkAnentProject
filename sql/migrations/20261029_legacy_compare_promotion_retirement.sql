-- Retire the unused public Compare share operation and empty pre-merge Promotion schema.
-- Never remove a Promotion database that contains rows; the guard fails before touching Auth.
USE bk_auth;

START TRANSACTION;
SELECT lock_id FROM auth_authorization_writer_lock WHERE lock_id = 1 FOR UPDATE;
SET @legacy_domain_retirement = NOT EXISTS (
    SELECT 1 FROM auth_authorization_migration
    WHERE migration_id = '20261029_legacy_compare_promotion_retirement'
);
COMMIT;

DROP PROCEDURE IF EXISTS retire_empty_legacy_promotion_database;
DELIMITER $$
CREATE PROCEDURE retire_empty_legacy_promotion_database()
BEGIN
    DECLARE schema_present BOOLEAN DEFAULT FALSE;
    DECLARE tables_finished BOOLEAN DEFAULT FALSE;
    DECLARE table_name_value VARCHAR(64);
    DECLARE table_rows BIGINT UNSIGNED DEFAULT 0;
    DECLARE total_rows BIGINT UNSIGNED DEFAULT 0;
    DECLARE table_cursor CURSOR FOR
        SELECT table_name
        FROM information_schema.tables
        WHERE table_schema = 'bk_promotion' AND table_type = 'BASE TABLE';
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET tables_finished = TRUE;

    IF @legacy_domain_retirement THEN
        SELECT EXISTS (
            SELECT 1 FROM information_schema.schemata WHERE schema_name = 'bk_promotion'
        ) INTO schema_present;
        IF schema_present THEN
            OPEN table_cursor;
            read_tables: LOOP
                FETCH table_cursor INTO table_name_value;
                IF tables_finished THEN
                    LEAVE read_tables;
                END IF;
                SET @legacy_count_sql = CONCAT(
                    'SELECT COUNT(*) INTO @legacy_table_rows FROM `bk_promotion`.`',
                    REPLACE(table_name_value, '`', '``'), '`'
                );
                SET @legacy_table_rows = 0;
                PREPARE legacy_count_statement FROM @legacy_count_sql;
                EXECUTE legacy_count_statement;
                DEALLOCATE PREPARE legacy_count_statement;
                SET table_rows = COALESCE(@legacy_table_rows, 0);
                SET total_rows = total_rows + table_rows;
            END LOOP;
            CLOSE table_cursor;

            IF total_rows <> 0 THEN
                SIGNAL SQLSTATE '45000'
                    SET MESSAGE_TEXT = 'Legacy bk_promotion contains rows; retirement refused';
            END IF;
            DROP DATABASE bk_promotion;
        END IF;
    END IF;
END$$
DELIMITER ;

CALL retire_empty_legacy_promotion_database();
DROP PROCEDURE retire_empty_legacy_promotion_database;

START TRANSACTION;
SELECT lock_id FROM auth_authorization_writer_lock WHERE lock_id = 1 FOR UPDATE;
SET @legacy_domain_retirement = NOT EXISTS (
    SELECT 1 FROM auth_authorization_migration
    WHERE migration_id = '20261029_legacy_compare_promotion_retirement'
);

DELETE FROM auth_role_permission
WHERE @legacy_domain_retirement
  AND BINARY permission_code IN ('compare.report.share.public');

UPDATE auth_permission
SET active = 0, version = version + 1
WHERE @legacy_domain_retirement
  AND BINARY permission_code = 'compare.report.share.public'
  AND active <> 0;

UPDATE auth_user_authorization_state
SET permission_revision = permission_revision + 1,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE @legacy_domain_retirement;

INSERT INTO auth_authorization_outbox (event_id, affected_users, revisions, reason)
SELECT CONCAT('legacy-share-permission-', s.user_id),
       CONCAT('[', s.user_id, ']'),
       JSON_ARRAY(JSON_OBJECT('userId', s.user_id,
                              'permissionRevision', s.permission_revision,
                              'credentialEpoch', s.credential_epoch,
                              'status', s.status)),
       'CATALOG_CHANGE'
FROM auth_user_authorization_state s
WHERE @legacy_domain_retirement;

INSERT INTO auth_authorization_migration (migration_id)
SELECT '20261029_legacy_compare_promotion_retirement' WHERE @legacy_domain_retirement;
COMMIT;
