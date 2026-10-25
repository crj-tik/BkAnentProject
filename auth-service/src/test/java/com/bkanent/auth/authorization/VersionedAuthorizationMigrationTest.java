package com.bkanent.auth.authorization;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** H2 seed/repeat verification plus static MySQL guards; not a MySQL migration acceptance claim. */
class VersionedAuthorizationMigrationTest {
    @Test
    void freshSeedAndRepeatAreExecutableWithoutDemoCredentials() throws Exception {
        var fixture = fixture();
        applySeed(fixture);
        assertEquals(2, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_role", Integer.class));
        assertEquals(38, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_permission", Integer.class));
        assertEquals(0, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM user_account", Integer.class));
        applySeed(fixture);
        assertEquals(38, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_permission", Integer.class));
        assertEquals(1, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_migration", Integer.class));
    }

    @Test
    void upgradePreservesUnknownRowsAndRepeatDoesNotResurrectRevocation() throws Exception {
        var fixture = fixture();
        fixture.jdbc.update("INSERT INTO auth_role(id,role_code,role_name) VALUES (7,'UNKNOWN','unknown')");
        fixture.jdbc.update("INSERT INTO user_account(id,username,password_hash,display_name,role_code,tenant_code) VALUES (1,'a','existing','a','ADMIN','t'),(2,'b','existing','b','BROKER','t'),(3,'x','existing','x','UNKNOWN','t')");
        applySeed(fixture);
        assertEquals(1, fixture.jdbc.queryForObject("SELECT business_admin FROM user_account WHERE id=1", Integer.class));
        assertEquals(0, fixture.jdbc.queryForObject("SELECT business_admin FROM user_account WHERE id=3", Integer.class));
        assertEquals(0, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_user_role WHERE user_id=3", Integer.class));
        assertEquals(0, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_role_permission WHERE role_id=7", Integer.class));
        assertEquals(3, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_outbox", Integer.class));
        fixture.jdbc.update("UPDATE user_account SET business_admin=0 WHERE id=1");
        fixture.jdbc.update("UPDATE auth_user_role SET active=0 WHERE user_id=2");
        fixture.jdbc.update("UPDATE auth_permission SET active=0,version=5 WHERE permission_code='listing.read'");
        fixture.jdbc.update("DELETE FROM auth_role_permission WHERE permission_code='listing.read'");
        applySeed(fixture);
        assertEquals(0, fixture.jdbc.queryForObject("SELECT business_admin FROM user_account WHERE id=1", Integer.class));
        assertEquals(0, fixture.jdbc.queryForObject("SELECT active FROM auth_user_role WHERE user_id=2", Integer.class));
        assertEquals(0, fixture.jdbc.queryForObject("SELECT active FROM auth_permission WHERE permission_code='listing.read'", Integer.class));
        assertEquals(0, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_role_permission WHERE permission_code='listing.read'", Integer.class));
        assertEquals(3, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_outbox", Integer.class));
        assertEquals(3, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM user_account", Integer.class));
    }

    @Test
    void mysqlDDLHasIncrementalGuardsExactPermissionCollationAndReplayLeaseColumns() throws Exception {
        String sql = Files.readString(AuthorizationRepositoryTest.migrationPath());
        assertFalse(Pattern.compile("(?i)DROP\\s+(TABLE|DATABASE)|TRUNCATE|DELETE\\s+FROM\\s+user_account").matcher(sql).find());
        assertTrue(sql.contains("column_name = 'business_admin'"));
        assertTrue(sql.contains("column_name = 'valid_from'"));
        assertTrue(sql.contains("column_name = 'valid_until'"));
        assertTrue(sql.contains("column_name = 'active'"));
        assertTrue(sql.contains("permission_code VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin"));
        assertTrue(sql.contains("lease_epoch BIGINT"));
        assertTrue(sql.contains("PRIMARY KEY (caller, key_id, nonce_hash)"));
        assertTrue(sql.contains("SET @authz_seed = NOT EXISTS"));
        assertTrue(sql.contains("BINARY u.role_code IN ('ADMIN', 'BROKER')"));
        assertFalse(sql.contains("'agent.workflow.invoke.generic'"));
        assertFalse(sql.contains("'agent.mcp.server.generic.read'"));
    }

    private Fixture fixture() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:migration" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        String migration = Files.readString(AuthorizationRepositoryTest.migrationPath());
        var tables = Pattern.compile("CREATE TABLE IF NOT EXISTS [\\s\\S]+?;", Pattern.CASE_INSENSITIVE).matcher(migration);
        while (tables.find()) {
            jdbc.execute(tables.group().replaceAll("CHARACTER SET ascii COLLATE ascii_bin", "")
                    .replaceAll("ON UPDATE CURRENT_TIMESTAMP(?:\\(6\\))?", "")
                    .replaceAll("ENGINE=InnoDB[^;]*", ""));
        }
        jdbc.execute("ALTER TABLE user_account ADD COLUMN business_admin TINYINT NOT NULL DEFAULT 0");
        jdbc.update("INSERT INTO auth_authorization_writer_lock VALUES (1)");
        return new Fixture(jdbc, new TransactionTemplate(new DataSourceTransactionManager(source)), migration);
    }

    private void applySeed(Fixture fixture) {
        fixture.transaction.executeWithoutResult(status -> {
            boolean first = fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_migration", Integer.class) == 0;
            String seed = fixture.migration.substring(fixture.migration.indexOf("START TRANSACTION;"));
            seed = seed.replaceAll("(?m)--[^\\r\\n]*", "");
            for (String statement : seed.split(";")) {
                String sql = statement.trim();
                if (sql.isEmpty() || sql.equals("START TRANSACTION") || sql.equals("COMMIT") || sql.startsWith("SET @authz_seed")) continue;
                sql = sql.replace("@authz_seed", first ? "TRUE" : "FALSE")
                        .replace("BINARY ", ""); // MySQL exact-byte comparison; H2 strings are case sensitive.
                // MySQL UPDATE JOIN spelling only; same guarded active/deleted ADMIN predicate.
                if (sql.startsWith("UPDATE user_account u JOIN auth_role r")) {
                    sql = "UPDATE user_account u SET business_admin = 1 WHERE " + (first ? "TRUE" : "FALSE")
                            + " AND u.role_code = 'ADMIN' AND EXISTS (SELECT 1 FROM auth_role r WHERE r.role_code=u.role_code AND r.active=1 AND r.deleted=0)";
                }
                fixture.jdbc.execute(sql);
            }
        });
    }

    private record Fixture(JdbcTemplate jdbc, TransactionTemplate transaction, String migration) { }
}
