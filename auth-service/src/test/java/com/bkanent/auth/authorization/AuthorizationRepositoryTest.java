package com.bkanent.auth.authorization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class AuthorizationRepositoryTest {
    JdbcTemplate jdbc;
    AuthorizationRepository repository;
    private final Instant now = Instant.parse("2026-10-23T10:00:00Z");

    @BeforeEach
    void setup() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        // Use migration DDL, adapting only MySQL storage/charset clauses for H2. Predicates are unmodified.
        String migration = Files.readString(migrationPath());
        var tables = Pattern.compile("CREATE TABLE IF NOT EXISTS [\\s\\S]+?;", Pattern.CASE_INSENSITIVE)
                .matcher(migration);
        while (tables.find()) {
            String sql = tables.group().replaceAll("CHARACTER SET ascii COLLATE ascii_bin", "")
                    .replaceAll("ON UPDATE CURRENT_TIMESTAMP(?:\\(6\\))?", "")
                    .replaceAll("ENGINE=InnoDB[^;]*", "");
            jdbc.execute(sql);
        }
        jdbc.execute("ALTER TABLE user_account ADD COLUMN business_admin TINYINT NOT NULL DEFAULT 0");
        jdbc.execute("CREATE TABLE auth_user_business_identity (user_id BIGINT NOT NULL, identity_type VARCHAR(24) NOT NULL, identity_id BIGINT NOT NULL, PRIMARY KEY(user_id,identity_type), UNIQUE(identity_type,identity_id))");
        jdbc.update("INSERT INTO auth_authorization_writer_lock VALUES (1)");
        repository = new AuthorizationRepository(jdbc, new DataSourceTransactionManager(dataSource));
        user(1, false); user(2, false); user(3, true);
        jdbc.update("INSERT INTO auth_role(id,role_code,role_name,active,assignable,deleted) VALUES (10,'A','A',1,0,0),(20,'B','B',1,1,0),(30,'OFF','OFF',0,1,0),(40,'GONE','GONE',1,1,1)");
        jdbc.update("INSERT INTO auth_permission(permission_code,domain,operation,active,version) VALUES ('listing.read','listing','read',1,1),('auth.users.manage','auth','users.manage',1,2),('disabled.read','disabled','read',0,3)");
        jdbc.update("INSERT INTO auth_role_permission VALUES (10,'listing.read'),(10,'invented.read'),(20,'listing.read'),(20,'auth.users.manage'),(20,'disabled.read'),(30,'auth.users.manage'),(40,'auth.users.manage')");
    }

    @Test
    void jdbcPredicatesExcludeInactiveDeletedExpiredAndUnregistered() {
        grant(1, 10, 1, null, now.plusSeconds(60));
        grant(1, 20, 1, null, now);
        grant(1, 30, 1, null, null);
        grant(1, 40, 1, null, null);
        var snapshot = repository.snapshot(1, now).orElseThrow();
        assertEquals(List.of("listing.read"), snapshot.permissions());
        assertEquals(now.plusSeconds(60), snapshot.grantDeadline());
        assertEquals(1, repository.loadFacts(1, now).orElseThrow().grants().size());
        // assignable=0 only forbids new assignments; existing live grants still apply.
        assertTrue(snapshot.hasPermission("listing.read", now));
    }

    @Test
    void jdbcMultiRoleAndFutureWindowAndGrantInactiveAreHandled() {
        grant(1, 10, 1, null, null);
        grant(1, 20, 1, now.plusSeconds(30), null);
        assertEquals(List.of("listing.read"), repository.snapshot(1, now).orElseThrow().permissions());
        assertEquals(now.plusSeconds(30), repository.snapshot(1, now).orElseThrow().grantDeadline());
        assertEquals(List.of("auth.users.manage", "listing.read"),
                repository.snapshot(1, now.plusSeconds(30)).orElseThrow().permissions());
        jdbc.update("UPDATE auth_user_role SET active=0 WHERE user_id=1 AND role_id=20");
        assertEquals(List.of("listing.read"), repository.snapshot(1, now.plusSeconds(30)).orElseThrow().permissions());
    }

    @Test
    void noRoleAdminWorksButMissingStateDisabledDeletedAndUnknownFailClosed() {
        assertEquals(List.of("auth.users.manage", "listing.read"), repository.snapshot(3, now).orElseThrow().permissions());
        assertFalse(repository.snapshot(3, now).orElseThrow().hasPermission("invented.manage", now));
        jdbc.update("UPDATE user_account SET account_status='DISABLED' WHERE id=3");
        assertTrue(repository.snapshot(3, now).orElseThrow().permissions().isEmpty());
        jdbc.update("UPDATE user_account SET account_status='ACTIVE',deleted=1 WHERE id=3");
        assertEquals("DELETED", repository.snapshot(3, now).orElseThrow().status());
        jdbc.update("DELETE FROM auth_user_authorization_state WHERE user_id=2");
        assertTrue(repository.snapshot(2, now).isEmpty());
        assertTrue(repository.snapshot(404, now).isEmpty());
    }

    @Test
    void authBusinessIdentityIsExplicitAndPartOfTheCalculatedSnapshot() {
        jdbc.update("INSERT INTO auth_user_business_identity(user_id,identity_type,identity_id) VALUES (1,'BROKER',901)");
        assertEquals(java.util.Map.of("BROKER", 901L), repository.snapshot(1, now).orElseThrow().businessIdentities());
        assertTrue(repository.snapshot(3, now).orElseThrow().businessIdentities().isEmpty());
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> jdbc.update(
                "INSERT INTO auth_user_business_identity(user_id,identity_type,identity_id) VALUES (2,'BROKER',901)"));
    }

    @Test
    void mutationVersionsStateAndOutboxAreAtomicAndOrdered() {
        var result = repository.mutateUsers(List.of(2L, 1L, 2L), "ACCOUNT_DISABLED", true,
                template -> template.update("UPDATE user_account SET account_status='DISABLED' WHERE id IN (1,2)"));
        assertEquals(List.of(1L, 2L), result.versions().stream().map(AuthorizationRepository.UserVersion::userId).toList());
        assertTrue(result.versions().stream().allMatch(v -> v.permissionRevision() == 2
                && v.credentialEpoch() == 2 && v.status().equals("DISABLED")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_outbox", Integer.class));
        assertEquals("[1, 2]", jdbc.queryForObject("SELECT affected_users FROM auth_authorization_outbox", String.class));
        assertThrows(IllegalStateException.class, () -> repository.mutateUsers(List.of(1L), "TEST_ROLLBACK", false,
                template -> { template.update("UPDATE user_account SET business_admin=1 WHERE id=1"); throw new IllegalStateException(); }));
        assertEquals(0, jdbc.queryForObject("SELECT business_admin FROM user_account WHERE id=1", Integer.class));
        assertEquals(2, repository.currentVersion(1).orElseThrow().permissionRevision());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_outbox", Integer.class));
    }

    @Test
    void permissionChangeIncludesLinkedUsersAndNoRoleAdminAtomically() {
        grant(1, 10, 1, null, null);
        grant(2, 10, 0, null, now.minusSeconds(10));
        user(4, false); // No role/admin, but the global catalogVersion still changes its stable snapshot.
        var changed = repository.mutatePermission("listing.read", "PERMISSION_DISABLED",
                template -> template.update("UPDATE auth_permission SET active=0,version=version+1 WHERE permission_code='listing.read'"));
        assertEquals(List.of(1L,2L,3L,4L), changed.versions().stream().map(AuthorizationRepository.UserVersion::userId).toList());
        assertTrue(repository.snapshot(1, now).orElseThrow().permissions().isEmpty());
        assertFalse(repository.snapshot(3, now).orElseThrow().hasPermission("listing.read", now));
        assertThrows(IllegalStateException.class, () -> repository.mutatePermission("listing.read", "ROLLBACK", template -> {
            template.update("UPDATE auth_permission SET active=1 WHERE permission_code='listing.read'");
            throw new IllegalStateException();
        }));
        assertEquals(0, jdbc.queryForObject("SELECT active FROM auth_permission WHERE permission_code='listing.read'", Integer.class));
        assertEquals(2, repository.currentVersion(3).orElseThrow().permissionRevision());
    }

    @Test
    void roleAndCatalogChangesSelectAffectedUsers() {
        grant(1, 10, 1, null, null);
        var role = repository.mutateRole(10, "ROLE_DISABLED", template -> template.update("UPDATE auth_role SET active=0 WHERE id=10"));
        assertEquals(List.of(1L), role.versions().stream().map(AuthorizationRepository.UserVersion::userId).toList());
        var catalog = repository.mutateCatalog("CATALOG_CHANGED", template -> template.update("UPDATE auth_permission SET version=4 WHERE permission_code='listing.read'"));
        assertEquals(3, catalog.versions().size());
    }

    @Test
    void leaseEpochRejectsLateCompletionRetryAndSupportsRecovery() {
        String event = repository.mutateUsers(List.of(1L), "TEST_CHANGE", false, ignored -> { }).eventId();
        // created_at/default next_attempt_at use DB clock; explicit fixture clock avoids wall-time dependence.
        jdbc.update("UPDATE auth_authorization_outbox SET next_attempt_at=?", Timestamp.from(now));
        var first = repository.claimOutbox(1, now, Duration.ofSeconds(10)).get(0);
        assertTrue(repository.claimOutbox(1, now.plusSeconds(9), Duration.ofSeconds(10)).isEmpty());
        var second = repository.claimOutbox(1, now.plusSeconds(10), Duration.ofSeconds(10)).get(0);
        assertEquals(first.leaseEpoch()+1, second.leaseEpoch());
        assertFalse(repository.completeOutbox(event, first.leaseEpoch(), now.plusSeconds(11)));
        assertFalse(repository.retryOutbox(event, first.leaseEpoch(), now.plusSeconds(11), now.plusSeconds(12)));
        assertTrue(repository.retryOutbox(event, second.leaseEpoch(), now.plusSeconds(11), now.plusSeconds(15)));
        assertTrue(repository.claimOutbox(1, now.plusSeconds(14), Duration.ofSeconds(10)).isEmpty());
        var third = repository.claimOutbox(1, now.plusSeconds(15), Duration.ofSeconds(10)).get(0);
        assertTrue(repository.completeOutbox(event, third.leaseEpoch(), now.plusSeconds(16)));
        assertFalse(repository.completeOutbox(event, third.leaseEpoch(), now.plusSeconds(16)));
        assertThrows(IllegalArgumentException.class, () -> repository.claimOutbox(101, now, Duration.ofSeconds(10)));
    }

    @Test
    void concurrentWritersCannotLoseVersionsAndProduceOneEventEach() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var one = pool.submit(() -> { start.await(); return repository.mutateUsers(List.of(2L,1L), "CONCURRENT", false, ignored -> { }); });
            var two = pool.submit(() -> { start.await(); return repository.mutateUsers(List.of(1L,2L), "CONCURRENT", false, ignored -> { }); });
            start.countDown();
            one.get(10, TimeUnit.SECONDS); two.get(10, TimeUnit.SECONDS);
            assertEquals(3, repository.currentVersion(1).orElseThrow().permissionRevision());
            assertEquals(3, repository.currentVersion(2).orElseThrow().permissionRevision());
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_outbox", Integer.class));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void nonceReplayFenceIsPersistentAndCallerKeyScoped() {
        String hash = "a".repeat(64);
        assertTrue(repository.consumeNonce("agent-service", "key1", hash, now, now.plusSeconds(60)));
        assertFalse(repository.consumeNonce("agent-service", "key1", hash, now, now.plusSeconds(60)));
        assertTrue(repository.consumeNonce("agent-service", "key2", hash, now, now.plusSeconds(60)));
        assertThrows(IllegalArgumentException.class, () -> repository.consumeNonce("agent-service", "key1", "raw", now, now.plusSeconds(60)));
        assertThrows(IllegalArgumentException.class, () -> repository.consumeNonce("agent-service", "key1", hash, now, now));
    }

    private void user(long id, boolean admin) {
        jdbc.update("INSERT INTO user_account(id,username,password_hash,display_name,role_code,tenant_code,business_admin) VALUES (?,?, 'not-a-credential','test','UNKNOWN','test',?)", id, "user"+id, admin ? 1 : 0);
        jdbc.update("INSERT INTO auth_user_authorization_state(user_id,status) VALUES (?,'ACTIVE')", id);
    }

    private void grant(long user, long role, int active, Instant from, Instant until) {
        jdbc.update("INSERT INTO auth_user_role(user_id,role_id,active,valid_from,valid_until) VALUES (?,?,?,?,?)",
                user, role, active, from == null ? null : Timestamp.from(from), until == null ? null : Timestamp.from(until));
    }

    static Path migrationPath() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/migrations/20261023_versioned_authorization.sql"))) {
            root = root.getParent();
        }
        if (root == null) throw new IllegalStateException("Repository migration not found");
        return root.resolve("sql/migrations/20261023_versioned_authorization.sql");
    }
}
