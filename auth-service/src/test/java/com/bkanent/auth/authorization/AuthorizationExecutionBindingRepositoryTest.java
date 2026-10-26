package com.bkanent.auth.authorization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static com.bkanent.auth.authorization.AuthorizationRepository.BindingResult.*;
import static org.junit.jupiter.api.Assertions.*;

class AuthorizationExecutionBindingRepositoryTest {
    private JdbcTemplate jdbc;
    private AuthorizationRepository repository;
    private Instant now;
    private Binding original;

    @BeforeEach
    void setup() throws Exception {
        var fixture = new AuthorizationRepositoryTest();
        fixture.setup();
        jdbc = fixture.jdbc;
        repository = fixture.repository;
        applyBindingDdl(jdbc);
        // Freeze the original absolute deadlines once; retries never derive them from a new proof time.
        now = Instant.ofEpochSecond(Instant.now().getEpochSecond());
        original = new Binding();
        original.expires = now.plusSeconds(240).getEpochSecond();
        original.hard = now.plusSeconds(300).getEpochSecond();
    }

    @Test
    void newAndIdenticalRetryPreserveEveryFactWithoutAuthorizationMutation() {
        assertEquals(CREATED, bind(original));
        assertEquals(IDEMPOTENT, bind(original));
        assertEquals(1, rows("auth_authorization_execution_binding"));
        assertEquals(1, repository.currentVersion(1).orElseThrow().permissionRevision());
        assertEquals(1, repository.currentVersion(1).orElseThrow().credentialEpoch());
        assertEquals(0, rows("auth_authorization_outbox"));
        assertEquals(0, rows("auth_authorization_nonce"));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM auth_authorization_execution_binding
                WHERE ingress_request_hash=? AND tuple_hash=? AND actor_id=? AND caller=?
                  AND run_id=? AND session_id=? AND policy_digest=? AND operations_hash=?
                  AND execution_hash=? AND expires_at=? AND hard_deadline=? AND created_at IS NOT NULL
                """, Integer.class, original.ingress, original.tuple, original.actor, original.caller,
                original.run, original.session, original.policy, original.operations, original.execution,
                original.expires, original.hard));
    }

    @Test
    void changedActorConflictsEvenWhenTupleHashIsReused() {
        assertConflictingField(binding -> binding.actor = 2);
    }

    @Test
    void changedCallerConflictsEvenForTheSameActor() {
        assertConflictingField(binding -> binding.caller = "gateway-service");
    }

    @Test
    void changedRunConflictsAndCaseIsNotNormalized() {
        assertConflictingField(binding -> binding.run = "RUN-original");
    }

    @Test
    void changedSessionConflictsAndCaseIsNotNormalized() {
        assertConflictingField(binding -> binding.session = "SESSION-original");
    }

    @Test
    void changedPolicyConflicts() {
        assertConflictingField(binding -> binding.policy = "f".repeat(64));
    }

    @Test
    void changedOperationsScopeConflicts() {
        assertConflictingField(binding -> binding.operations = "f".repeat(64));
    }

    @Test
    void changedExecutionInputConflicts() {
        assertConflictingField(binding -> binding.execution = "f".repeat(64));
    }

    @Test
    void changedExpiryConflictsEvenWhenShorter() {
        assertConflictingField(binding -> binding.expires--);
    }

    @Test
    void changedHardDeadlineConflictsEvenWhenShorter() {
        assertConflictingField(binding -> binding.hard--);
    }

    @Test
    void differentTupleHashConflictsWithoutOverwritingOriginalFacts() {
        assertEquals(CREATED, bind(original));
        Binding different = original.copy();
        different.tuple = "f".repeat(64);
        assertEquals(CONFLICT, bind(different));
        assertEquals(IDEMPOTENT, bind(original));
        assertEquals(1, rows("auth_authorization_execution_binding"));
    }

    @Test
    void anotherIngressIsIndependentAndTupleHashIsNotGloballyUnique() {
        assertEquals(CREATED, bind(original));
        Binding another = original.copy();
        another.ingress = "f".repeat(64);
        assertEquals(CREATED, bind(another));
        assertEquals(2, rows("auth_authorization_execution_binding"));
        assertEquals(0, rows("auth_authorization_outbox"));
    }

    @Test
    void epochZeroIsAcceptedButNegativeEpochIsInvalid() {
        jdbc.update("UPDATE auth_user_authorization_state SET credential_epoch=0 WHERE user_id=1");
        original.epoch = 0;
        assertEquals(CREATED, bind(original));
        assertEquals(IDEMPOTENT, bind(original));
        Binding invalid = original.copy();
        invalid.epoch = -1;
        assertThrows(IllegalArgumentException.class, () -> bind(invalid));
    }

    @Test
    void expiredIngressAndExpiredRetryAreRejectedWithoutRebinding() {
        Binding expired = original.copy();
        expired.expires = now.getEpochSecond();
        assertEquals(CONFLICT, bind(expired));
        expired.expires = now.minusSeconds(1).getEpochSecond();
        expired.hard = now.getEpochSecond();
        assertEquals(CONFLICT, bind(expired));
        assertEquals(0, rows("auth_authorization_execution_binding"));
        // A historical, correctly shaped fact is never replaced or treated as fresh authorization.
        insertFact(expired);
        assertEquals(CONFLICT, bind(expired));
        assertEquals(CONFLICT, bind(original));
        assertEquals(1, rows("auth_authorization_execution_binding"));
    }

    @Test
    void currentRevisionAndEpochAreRequiredForNewAndIdempotentBindings() {
        assertEquals(CREATED, bind(original));
        repository.mutateUsers(List.of(1L), "TEST_VERSION_CHANGE", true, ignored -> { });
        assertEquals(CONFLICT, bind(original));
        Binding freshIngress = original.copy();
        freshIngress.ingress = "f".repeat(64);
        assertEquals(CONFLICT, bind(freshIngress));
        freshIngress.revision = 2;
        assertEquals(CONFLICT, bind(freshIngress));
        freshIngress.epoch = 2;
        assertEquals(CREATED, bind(freshIngress));
        assertEquals(2, rows("auth_authorization_execution_binding"));
        assertEquals(1, rows("auth_authorization_outbox"));
    }

    @Test
    void inactiveDeletedStateMismatchMissingStateAndUnknownActorFailClosed() {
        jdbc.update("UPDATE user_account SET account_status='DISABLED' WHERE id=1");
        assertEquals(CONFLICT, bind(original));
        jdbc.update("UPDATE user_account SET account_status='ACTIVE',deleted=1 WHERE id=1");
        assertEquals(CONFLICT, bind(original));
        jdbc.update("UPDATE user_account SET deleted=0 WHERE id=1");
        jdbc.update("UPDATE auth_user_authorization_state SET status='DISABLED' WHERE user_id=1");
        assertEquals(CONFLICT, bind(original));
        jdbc.update("DELETE FROM auth_user_authorization_state WHERE user_id=1");
        assertEquals(CONFLICT, bind(original));
        original.actor = 404;
        assertEquals(CONFLICT, bind(original));
        assertEquals(0, rows("auth_authorization_execution_binding"));
        assertEquals(0, rows("auth_authorization_outbox"));
    }

    @Test
    void dueGrantStartsAndEndsAreProcessedUnderMutexBeforeExpectedRevisionCheck() {
        jdbc.update("INSERT INTO auth_user_role(user_id,role_id,active,valid_from,valid_until) VALUES (1,10,1,?,?)",
                Timestamp.from(now.minusSeconds(20)), Timestamp.from(now.minusSeconds(10)));
        assertEquals(CONFLICT, bind(original));
        assertEquals(0, rows("auth_authorization_execution_binding"));
        assertEquals(2, rows("auth_authorization_grant_boundary"));
        assertEquals(1, rows("auth_authorization_outbox"));
        assertEquals(2, repository.currentVersion(1).orElseThrow().permissionRevision());
        assertEquals(CONFLICT, bind(original));
        assertEquals(1, rows("auth_authorization_outbox"));
    }

    @Test
    void dueBoundaryAndNonceRollBackWhenProviderRejectsConflict() {
        jdbc.update("INSERT INTO auth_user_role(user_id,role_id,active,valid_until) VALUES (1,10,1,?)",
                Timestamp.from(now.minusSeconds(1)));
        String nonceHash = nonceHash("boundary-test-proof");
        assertThrows(IllegalStateException.class, () -> repository.withWriterLock(() -> {
            assertTrue(repository.consumeNonce(original.caller, "test-key", nonceHash, now, now.plusSeconds(60)));
            assertEquals(CONFLICT, bind(original));
            throw new IllegalStateException("Provider maps stale version to CONTEXT_CONFLICT");
        }));
        assertEquals(0, rows("auth_authorization_nonce"));
        assertEquals(0, rows("auth_authorization_execution_binding"));
        assertEquals(0, rows("auth_authorization_grant_boundary"));
        assertEquals(0, rows("auth_authorization_outbox"));
        assertEquals(1, repository.currentVersion(1).orElseThrow().permissionRevision());
    }

    @Test
    void newNonceAllowsIdempotentRetryButSameNonceRemainsReplay() {
        String first = nonceHash("first-test-proof");
        String second = nonceHash("new-test-proof");
        assertEquals(CREATED, repository.withWriterLock(() -> {
            assertTrue(repository.consumeNonce(original.caller, "test-key", first, now, now.plusSeconds(60)));
            return bind(original);
        }));
        assertFalse(repository.consumeNonce(original.caller, "test-key", first, now, now.plusSeconds(60)));
        assertEquals(IDEMPOTENT, repository.withWriterLock(() -> {
            assertTrue(repository.consumeNonce(original.caller, "test-key", second,
                    now.plusSeconds(1), now.plusSeconds(61)));
            return bind(original);
        }));
        assertEquals(2, rows("auth_authorization_nonce"));
        assertEquals(1, rows("auth_authorization_execution_binding"));
        assertEquals(List.of(first, second).stream().sorted().toList(), jdbc.queryForList(
                "SELECT nonce_hash FROM auth_authorization_nonce ORDER BY nonce_hash", String.class));
        assertThrows(IllegalArgumentException.class, () -> repository.consumeNonce(original.caller,
                "test-key", "raw-test-nonce", now, now.plusSeconds(60)));
        List<String> columns = bindingColumns();
        assertEquals(List.of("ACTOR_ID", "CALLER", "CREATED_AT", "EXECUTION_HASH", "EXPIRES_AT",
                "HARD_DEADLINE", "INGRESS_REQUEST_HASH", "OPERATIONS_HASH", "POLICY_DIGEST",
                "RUN_ID", "SESSION_ID", "TUPLE_HASH"), columns);
        assertEquals(0, rows("auth_authorization_outbox"));
    }

    @Test
    void signingFailureRollsBackNewNonceAndBindingAndDoesNotIssueAnotherEpoch() {
        String nonceHash = nonceHash("signing-failure-test-proof");
        assertThrows(IllegalStateException.class, () -> repository.withWriterLock(() -> {
            assertTrue(repository.consumeNonce(original.caller, "test-key", nonceHash, now, now.plusSeconds(60)));
            assertEquals(CREATED, bind(original));
            throw new IllegalStateException("Synthetic local signature failure");
        }));
        assertEquals(0, rows("auth_authorization_nonce"));
        assertEquals(0, rows("auth_authorization_execution_binding"));
        assertEquals(0, rows("auth_authorization_outbox"));
        assertEquals(1, repository.currentVersion(1).orElseThrow().credentialEpoch());
        assertTrue(repository.consumeNonce(original.caller, "test-key", nonceHash, now, now.plusSeconds(60)));
        assertEquals(CREATED, bind(original));
    }

    @Test
    void conflictRollsBackOnlyNewNonceAndPreservesOriginalBinding() {
        assertEquals(CREATED, bind(original));
        Binding different = original.copy();
        different.run = "another-run";
        assertThrows(IllegalStateException.class, () -> repository.withWriterLock(() -> {
            assertTrue(repository.consumeNonce(original.caller, "test-key", nonceHash("conflict-test-proof"),
                    now, now.plusSeconds(60)));
            assertEquals(CONFLICT, bind(different));
            throw new IllegalStateException("Provider rejects tuple conflict");
        }));
        assertEquals(0, rows("auth_authorization_nonce"));
        assertEquals(IDEMPOTENT, bind(original));
        assertEquals(1, rows("auth_authorization_execution_binding"));
    }

    @Test
    void concurrentSameTupleWritersCreateOnlyOneFactAcrossRepositoryInstances() throws Exception {
        var second = new AuthorizationRepository(jdbc,
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var one = pool.submit(() -> { start.await(); return bind(original); });
            var two = pool.submit(() -> {
                start.await();
                return second.bindIngressExecution(original.ingress, original.tuple, original.actor,
                        original.caller, original.run, original.session, original.policy, original.operations,
                        original.execution, original.expires, original.hard, original.revision, original.epoch);
            });
            start.countDown();
            assertEquals(List.of(CREATED, IDEMPOTENT), List.of(one.get(10, TimeUnit.SECONDS),
                    two.get(10, TimeUnit.SECONDS)).stream().sorted().toList());
            assertEquals(1, rows("auth_authorization_execution_binding"));
            assertEquals(0, rows("auth_authorization_outbox"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentDifferentTupleWritersCannotRebindSameIngress() throws Exception {
        Binding different = original.copy();
        different.run = "another-run";
        different.tuple = "f".repeat(64);
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var one = pool.submit(() -> { start.await(); return bind(original); });
            var two = pool.submit(() -> { start.await(); return bind(different); });
            start.countDown();
            assertEquals(List.of(CREATED, CONFLICT), List.of(one.get(10, TimeUnit.SECONDS),
                    two.get(10, TimeUnit.SECONDS)).stream().sorted().toList());
            assertEquals(1, rows("auth_authorization_execution_binding"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void committedRoleChangeWinsMutexRaceAndStaleIngressCannotBeWritten() throws Exception {
        assertMutationRace(false);
    }

    @Test
    void committedCredentialInvalidationWinsMutexRaceAndStaleIngressCannotBeWritten() throws Exception {
        assertMutationRace(true);
    }

    @Test
    void metadataValidationRejectsUnboundedHashesIdsMalformedUnicodeAndInvalidPeriods() {
        List<Consumer<Binding>> invalid = List.of(
                binding -> binding.ingress = null,
                binding -> binding.tuple = "A".repeat(64),
                binding -> binding.policy = "a".repeat(63),
                binding -> binding.operations = "a".repeat(65),
                binding -> binding.execution = "raw-input",
                binding -> binding.actor = 0,
                binding -> binding.caller = "agent-service' OR 1=1 --",
                binding -> binding.caller = "a".repeat(97),
                binding -> binding.run = null,
                binding -> binding.run = "x".repeat(129),
                binding -> binding.run = "run\n",
                binding -> binding.run = "\uD800",
                binding -> binding.session = " ",
                binding -> binding.session = "session\u0000",
                binding -> binding.session = "\uDC00",
                binding -> binding.expires = 0,
                binding -> binding.hard = binding.expires - 1,
                binding -> binding.revision = 0,
                binding -> binding.epoch = -1);
        for (Consumer<Binding> change : invalid) {
            Binding value = original.copy();
            change.accept(value);
            assertThrows(IllegalArgumentException.class, () -> bind(value));
        }
        assertEquals(0, rows("auth_authorization_execution_binding"));
        assertEquals(0, rows("auth_authorization_outbox"));
    }

    @Test
    void boundedUnicodeAndSqlLookingIdentifiersRemainLiteralPreparedValues() {
        original.run = "任务' OR 1=1 --";
        original.session = "会话-\uD83D\uDE00";
        assertEquals(CREATED, bind(original));
        assertEquals(IDEMPOTENT, bind(original));
        assertEquals(3, rows("user_account"));
        assertEquals(original.run, jdbc.queryForObject(
                "SELECT run_id FROM auth_authorization_execution_binding WHERE ingress_request_hash=?",
                String.class, original.ingress));
    }

    @Test
    void actualDdlChecksDigestAndDeadlinePredicatesAndUniqueIngress() {
        Binding invalid = original.copy();
        invalid.ingress = "A".repeat(64);
        assertThrows(DataAccessException.class, () -> insertFact(invalid));
        Binding invalidPeriod = original.copy();
        invalidPeriod.hard = invalidPeriod.expires - 1;
        assertThrows(DataAccessException.class, () -> insertFact(invalidPeriod));
        assertEquals(CREATED, bind(original));
        assertThrows(DuplicateKeyException.class, () -> insertFact(original));
        assertEquals(1, rows("auth_authorization_execution_binding"));
    }

    @Test
    void migrationSupportsFreshTableUpgradeAndRepeatWithoutSeedsOrUnknownRowChanges() throws Exception {
        var empty = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:bindingFresh-"
                + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        applyBindingDdl(empty);
        applyBindingDdl(empty);
        assertEquals(0, empty.queryForObject("SELECT COUNT(*) FROM auth_authorization_execution_binding", Integer.class));
        // Existing unknown legacy roles/users are preserved; applying this DDL never grants them anything.
        assertEquals(CREATED, bind(original));
        applyBindingDdl(jdbc);
        applyBindingDdl(jdbc);
        assertEquals(IDEMPOTENT, bind(original));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM user_account WHERE role_code='UNKNOWN'", Integer.class));
        assertEquals(0, rows("auth_user_role"));
        assertEquals(0, rows("auth_authorization_outbox"));
        String migration = Files.readString(bindingMigrationPath());
        assertFalse(Pattern.compile("(?im)^\\s*(INSERT|UPDATE|DELETE|ALTER|DROP|CREATE DATABASE|PREPARE)\\b")
                .matcher(migration).find());
    }

    private void assertConflictingField(Consumer<Binding> change) {
        assertEquals(CREATED, bind(original));
        Binding different = original.copy();
        change.accept(different);
        // Check real fields even if the caller incorrectly reuses its original tuple hash.
        assertEquals(CONFLICT, bind(different));
        different.tuple = "f".repeat(64);
        assertEquals(CONFLICT, bind(different));
        assertEquals(IDEMPOTENT, bind(original));
        assertEquals(1, rows("auth_authorization_execution_binding"));
        assertEquals(0, rows("auth_authorization_outbox"));
    }

    private void assertMutationRace(boolean invalidateCredentials) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var mutationLocked = new CountDownLatch(1);
        var releaseMutation = new CountDownLatch(1);
        var bindingAttempted = new CountDownLatch(1);
        try {
            var mutation = pool.submit(() -> repository.mutateUsers(List.of(1L), "TEST_MUTEX_RACE",
                    invalidateCredentials, template -> {
                        if (!invalidateCredentials) {
                            template.update("INSERT INTO auth_user_role(user_id,role_id,active) VALUES (1,10,1)");
                        }
                        mutationLocked.countDown();
                        await(releaseMutation);
                    }));
            assertTrue(mutationLocked.await(5, TimeUnit.SECONDS));
            var binding = pool.submit(() -> { bindingAttempted.countDown(); return bind(original); });
            assertTrue(bindingAttempted.await(5, TimeUnit.SECONDS));
            releaseMutation.countDown();
            mutation.get(10, TimeUnit.SECONDS);
            assertEquals(CONFLICT, binding.get(10, TimeUnit.SECONDS));
            assertEquals(0, rows("auth_authorization_execution_binding"));
            assertEquals(1, rows("auth_authorization_outbox"));
            assertEquals(2, repository.currentVersion(1).orElseThrow().permissionRevision());
            assertEquals(invalidateCredentials ? 2 : 1,
                    repository.currentVersion(1).orElseThrow().credentialEpoch());
        } finally {
            releaseMutation.countDown();
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private AuthorizationRepository.BindingResult bind(Binding binding) {
        return repository.bindIngressExecution(binding.ingress, binding.tuple, binding.actor, binding.caller,
                binding.run, binding.session, binding.policy, binding.operations, binding.execution,
                binding.expires, binding.hard, binding.revision, binding.epoch);
    }

    private void insertFact(Binding binding) {
        jdbc.update("""
                INSERT INTO auth_authorization_execution_binding
                (ingress_request_hash,tuple_hash,actor_id,caller,run_id,session_id,policy_digest,
                 operations_hash,execution_hash,expires_at,hard_deadline) VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, binding.ingress, binding.tuple, binding.actor, binding.caller, binding.run, binding.session,
                binding.policy, binding.operations, binding.execution, binding.expires, binding.hard);
    }

    private int rows(String table) {
        // Test-owned constant identifiers only; production binding SQL never interpolates metadata.
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private List<String> bindingColumns() {
        return jdbc.execute((ConnectionCallback<List<String>>) connection -> {
            List<String> columns = new ArrayList<>();
            try (var result = connection.getMetaData().getColumns(null, null,
                    "AUTH_AUTHORIZATION_EXECUTION_BINDING", null)) {
                while (result.next()) columns.add(result.getString("COLUMN_NAME"));
            }
            return columns.stream().sorted().toList();
        });
    }

    private static String nonceHash(String nonce) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(nonce.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static Path bindingMigrationPath() {
        return AuthorizationRepositoryTest.migrationPath().resolveSibling("20261026_authorization_execution_binding.sql");
    }

    private static void applyBindingDdl(JdbcTemplate target) throws Exception {
        String migration = Files.readString(bindingMigrationPath());
        var tables = Pattern.compile("CREATE TABLE IF NOT EXISTS [\\s\\S]+?;", Pattern.CASE_INSENSITIVE)
                .matcher(migration);
        int count = 0;
        while (tables.find()) {
            // Adapt only MySQL storage/collation syntax. All types, uniqueness and CHECK predicates stay real.
            target.execute(tables.group().replaceAll("CHARACTER SET ascii COLLATE ascii_bin", "")
                    .replaceAll("CHARACTER SET utf8mb4 COLLATE utf8mb4_bin", "")
                    .replaceAll("ENGINE=InnoDB[^;]*", ""));
            count++;
        }
        assertEquals(1, count);
    }

    private static final class Binding {
        String ingress = "a".repeat(64);
        String tuple = "b".repeat(64);
        long actor = 1;
        String caller = "agent-service";
        String run = "run-original";
        String session = "session-original";
        String policy = "c".repeat(64);
        String operations = "d".repeat(64);
        String execution = "e".repeat(64);
        long expires;
        long hard;
        long revision = 1;
        long epoch = 1;

        Binding copy() {
            Binding copy = new Binding();
            copy.ingress = ingress;
            copy.tuple = tuple;
            copy.actor = actor;
            copy.caller = caller;
            copy.run = run;
            copy.session = session;
            copy.policy = policy;
            copy.operations = operations;
            copy.execution = execution;
            copy.expires = expires;
            copy.hard = hard;
            copy.revision = revision;
            copy.epoch = epoch;
            return copy;
        }
    }
}
