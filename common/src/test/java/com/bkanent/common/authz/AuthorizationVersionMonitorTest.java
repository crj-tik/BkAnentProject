package com.bkanent.common.authz;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.bkanent.common.authz.AuthzTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class AuthorizationVersionMonitorTest {
    @Test
    void firstRegistrationReusesVerifiedEntryAndActionsNeverReadHeads() throws Exception {
        var fixture = new Fixture();
        var session = fixture.session("123", "one", NOW + 300);
        fixture.monitor.registry().register(session);
        fixture.monitor.registry().register(session);
        assertEquals(1, fixture.monitor.registry().sessionCount());
        for (int i = 0; i < 20; i++) session.requirePermission("memory.read");
        assertEquals(0, fixture.reads.get());
        assertEquals(0, fixture.monitor.reconcileBatch());
        assertEquals(AuthorizationLocalState.VALID, fixture.monitor.checkPlanning(session));
        assertEquals(1, fixture.reads.get());
        assertEquals(1, fixture.monitor.registry().sessionCount());
        assertEquals("AuthorizationVersionMonitor[REDACTED]", fixture.monitor.toString());
        assertEquals("AuthorizationRegistry[REDACTED]", fixture.monitor.registry().toString());
        assertEquals("authz:{123}:head", AuthorizationVersionMonitor.headKey("authz", "123"));
        assertEquals("authority_v1:{123}:head", AuthorizationVersionMonitor.headKey("authority_v1", "123"));
        assertThrows(AuthzException.class, () -> AuthorizationVersionMonitor.headKey("authz:*", "123"));
        assertThrows(AuthzException.class, () -> AuthorizationVersionMonitor.headKey("authz", "123}:other"));
    }

    @Test
    void transportFailuresRetainOnlyRemainingWindowThenDenyWithoutRenewal() throws Exception {
        var fixture = new Fixture();
        var session = fixture.session("123", "one", NOW + 300);
        fixture.monitor.registry().register(session);
        fixture.failure.set(new IllegalStateException("do-not-expose-transport-content"));
        fixture.clock.set(NOW + 10);
        assertEquals(AuthorizationLocalState.VALID, fixture.monitor.checkPlanning(session));
        session.requirePermission("memory.read");
        assertEquals(NOW + 30, session.effectiveDeadlineEpochSecond());
        fixture.clock.set(NOW + 29);
        fixture.monitor.reconcileBatch();
        session.requirePermission("memory.read");
        fixture.clock.set(NOW + 31);
        assertEquals(AuthorizationLocalState.EXPIRED, fixture.monitor.checkPlanning(session));
        assertCode(AuthzErrorCode.EXPIRED, () -> session.requirePermission("memory.read"));
        assertEquals(NOW + 30, session.effectiveDeadlineEpochSecond());
        assertEquals(1, fixture.monitor.registry().sessionCount());
    }

    @Test
    void missingOrRepeatedStaleHeadCannotTurnCacheReadsIntoAuthorityChecks() throws Exception {
        var fixture = new Fixture();
        var session = fixture.session("123", "one", NOW + 300);
        fixture.monitor.registry().register(session);
        for (long offset : List.of(10L, 20L, 29L, 31L, 40L)) {
            fixture.clock.set(NOW + offset);
            fixture.monitor.checkPlanning(session);
            assertEquals(NOW + 30, session.effectiveDeadlineEpochSecond());
        }
        assertEquals(AuthorizationLocalState.EXPIRED, session.state());
        fixture.heads.set(Map.of());
        fixture.monitor.checkPlanning(session);
        assertEquals(AuthorizationLocalState.EXPIRED, session.state());
        assertCode(AuthzErrorCode.EXPIRED, () -> session.requirePermission("memory.read"));
    }

    @Test
    void notificationLossIsDetectedByTenSecondUniqueUserPolling() throws Exception {
        var fixture = new Fixture();
        var first = fixture.session("123", "one", NOW + 300);
        var second = fixture.session("123", "two", NOW + 300);
        fixture.monitor.registry().register(first);
        fixture.monitor.registry().register(second);
        fixture.heads.set(Map.of("123", fixture.head("123", 8, 0, AuthorizationStatus.ACTIVE,
                first.context().permissionHash(), NOW + 9, NOW + 39)));
        fixture.clock.set(NOW + 9);
        assertEquals(0, fixture.monitor.reconcileBatch(fixture.clock));
        fixture.clock.set(NOW + 10);
        assertEquals(1, fixture.monitor.reconcileBatch(fixture.clock));
        assertEquals(List.of(List.of("123")), fixture.requests);
        assertEquals(AuthorizationLocalState.DIRTY, first.state());
        assertEquals(AuthorizationLocalState.DIRTY, second.state());
        assertEquals(7, first.context().permissionRevision());
        assertCode(AuthzErrorCode.CONTEXT_CONFLICT, () -> first.requirePermission("memory.read"));
        fixture.clock.set(NOW + 19);
        assertEquals(0, fixture.monitor.reconcileBatch());
        fixture.clock.set(NOW + 20);
        assertEquals(1, fixture.monitor.reconcileBatch());
        assertThrows(AuthzException.class, () -> fixture.monitor.reconcileBatch(Clock.systemUTC()));
    }

    @Test
    void sameVersionSignedRenewalRecoversFreshnessButNeverOriginalDeadlineOrScope() throws Exception {
        var fixture = new Fixture();
        var session = fixture.session("123", "one", NOW + 50);
        fixture.monitor.registry().register(session);
        var snapshot = session.snapshot();
        var context = session.context();
        fixture.clock.set(NOW + 31);
        assertEquals(AuthorizationLocalState.EXPIRED, session.state());
        fixture.heads.set(Map.of("123", fixture.head("123", 7, 0, AuthorizationStatus.ACTIVE,
                context.permissionHash(), NOW + 31, NOW + 61)));
        assertEquals(AuthorizationLocalState.VALID, fixture.monitor.checkPlanning(session));
        assertSame(snapshot, session.snapshot());
        assertSame(context, session.context());
        assertEquals(NOW + 50, session.effectiveDeadlineEpochSecond());
        assertEquals(PERMISSIONS, session.allowedOperations());
        assertEquals(AuthorizationLocalState.VALID, fixture.monitor.checkPlanning(session));
        fixture.clock.set(NOW + 50);
        assertEquals(AuthorizationLocalState.EXPIRED, session.state());
        assertCode(AuthzErrorCode.EXPIRED, () -> session.requirePermission("memory.read"));
        assertEquals(1, fixture.monitor.registry().cleanup());
        assertEquals(0, fixture.monitor.registry().sessionCount());
    }

    @Test
    void outOfOrderNoticesAndOldSignedRenewalsNeverClearDirtyState() throws Exception {
        var fixture = new Fixture();
        var session = fixture.session("123", "one", NOW + 300);
        fixture.monitor.registry().register(session);
        fixture.monitor.onNotification("124", 999, 999);
        fixture.monitor.onNotification("123", 6, 0);
        fixture.monitor.onNotification("123", 7, 0);
        assertEquals(AuthorizationLocalState.VALID, session.state());
        fixture.monitor.onNotification("123", 9, 0);
        fixture.monitor.onNotification("123", 8, 2);
        fixture.monitor.onNotification("123", 9, 1);
        fixture.clock.set(NOW + 1);
        fixture.heads.set(Map.of("123", fixture.head("123", 7, 0, AuthorizationStatus.ACTIVE,
                session.context().permissionHash(), NOW + 1, NOW + 31)));
        assertEquals(AuthorizationLocalState.DIRTY, fixture.monitor.checkPlanning(session));
        fixture.heads.set(Map.of("123", fixture.head("123", 9, 1, AuthorizationStatus.ACTIVE,
                session.context().permissionHash(), NOW + 1, NOW + 31)));
        assertEquals(AuthorizationLocalState.DIRTY, fixture.monitor.checkPlanning(session));
        assertEquals(7, session.context().permissionRevision());
        assertEquals(NOW + 30, session.effectiveDeadlineEpochSecond());
    }

    @Test
    void invalidSignaturesUsersHashesFutureProofsAndMalformedReadsFailClosed() throws Exception {
        var key = keyPair();
        var wrongKey = keyPair();
        for (int variant = 0; variant < 7; variant++) {
            var fixture = new Fixture(key);
            var session = fixture.session("123", "one", NOW + 300);
            fixture.monitor.registry().register(session);
            fixture.clock.set(NOW + 1);
            AuthzFreshness bad = switch (variant) {
                case 0 -> signedHead(wrongKey, "123", 7, 0, AuthorizationStatus.ACTIVE,
                        session.context().permissionHash(), NOW + 1, NOW + 31);
                case 1 -> fixture.head("124", 7, 0, AuthorizationStatus.ACTIVE,
                        session.context().permissionHash(), NOW + 1, NOW + 31);
                case 2 -> fixture.head("123", 7, 0, AuthorizationStatus.ACTIVE, HASH, NOW + 1, NOW + 31);
                case 3 -> fixture.head("123", 7, 0, AuthorizationStatus.ACTIVE,
                        session.context().permissionHash(), NOW + 2, NOW + 32);
                default -> null;
            };
            if (variant < 4) fixture.heads.set(Map.of("123", bad));
            else if (variant == 4) fixture.heads.set(null);
            else if (variant == 5) fixture.heads.set(java.util.Collections.singletonMap("123", null));
            else fixture.failure.set(new AuthzException(AuthzErrorCode.INVALID_ARGUMENT));
            assertEquals(AuthorizationLocalState.UNVERIFIED, fixture.monitor.checkPlanning(session));
            assertCode(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE, () -> session.requirePermission("memory.read"));
            fixture.failure.set(null);
            fixture.heads.set(Map.of("123", fixture.head("123", 7, 0, AuthorizationStatus.ACTIVE,
                    session.context().permissionHash(), NOW + 1, NOW + 31)));
            assertEquals(AuthorizationLocalState.UNVERIFIED, fixture.monitor.checkPlanning(session));
        }
    }

    @Test
    void correctlySignedInactiveHeadCannotContinueEvenAtSameRevision() throws Exception {
        var fixture = new Fixture();
        var session = fixture.session("123", "one", NOW + 300);
        fixture.monitor.registry().register(session);
        fixture.clock.set(NOW + 1);
        fixture.heads.set(Map.of("123", fixture.head("123", 7, 0, AuthorizationStatus.DISABLED,
                session.context().permissionHash(), NOW + 1, NOW + 31)));
        assertEquals(AuthorizationLocalState.UNVERIFIED, fixture.monitor.checkPlanning(session));
        assertCode(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE, () -> session.requirePermission("memory.read"));
        assertEquals(AuthorizationStatus.ACTIVE, session.freshness().status());
        assertEquals(NOW + 30, session.effectiveDeadlineEpochSecond());
    }

    @Test
    void identicalAttestationsAreNoopButChangedExpiryAtSameCheckFailsClosed() throws Exception {
        var fixture = new Fixture();
        var session = fixture.session("123", "one", NOW + 300);
        fixture.monitor.registry().register(session);
        for (int i = 0; i < 3; i++) {
            assertEquals(AuthorizationLocalState.VALID, fixture.monitor.checkPlanning(session));
        }
        fixture.heads.set(Map.of("123", fixture.head("123", 7, 0, AuthorizationStatus.ACTIVE,
                session.context().permissionHash(), NOW, NOW + 29)));
        assertEquals(AuthorizationLocalState.UNVERIFIED, fixture.monitor.checkPlanning(session));
        assertEquals(NOW + 30, session.effectiveDeadlineEpochSecond());
    }

    @Test
    void olderRenewalsNeverOverwriteLaterCheckAndForeignMapKeysAreIgnored() throws Exception {
        var fixture = new Fixture();
        var session = fixture.session("123", "one", NOW + 300);
        fixture.monitor.registry().register(session);
        fixture.clock.set(NOW + 10);
        fixture.heads.set(Map.of("123", fixture.head("123", 7, 0, AuthorizationStatus.ACTIVE,
                session.context().permissionHash(), NOW + 10, NOW + 40)));
        fixture.monitor.checkPlanning(session);
        fixture.heads.set(Map.of("123", fixture.head("123", 7, 0, AuthorizationStatus.ACTIVE,
                session.context().permissionHash(), NOW + 5, NOW + 35)));
        assertEquals(AuthorizationLocalState.VALID, fixture.monitor.checkPlanning(session));
        assertEquals(NOW + 40, session.effectiveDeadlineEpochSecond());
        fixture.heads.set(Map.of("124", fixture.head("124", 99, 9, AuthorizationStatus.ACTIVE,
                HASH, NOW + 10, NOW + 40)));
        assertEquals(AuthorizationLocalState.VALID, fixture.monitor.checkPlanning(session));
        assertEquals(NOW + 40, session.effectiveDeadlineEpochSecond());
    }

    @Test
    void registryBoundsAreAtomicUnregisterReleasesCapacityAndUnknownPlanningIsRejected() throws Exception {
        var fixture = new Fixture();
        var bounded = new AuthorizationVersionMonitor(fixture.reader(), Map.of(KID, fixture.key.getPublic()),
                fixture.clock, 2, 3, 2);
        var one = fixture.session("123", "one", NOW + 300);
        var two = fixture.session("123", "two", NOW + 300);
        var three = fixture.session("124", "three", NOW + 300);
        bounded.registry().register(one);
        bounded.registry().register(two);
        assertCode(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE,
                () -> bounded.registry().register(fixture.session("123", "overflow", NOW + 300)));
        bounded.registry().register(three);
        assertCode(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE,
                () -> bounded.registry().register(fixture.session("125", "overflow", NOW + 300)));
        assertEquals(2, bounded.registry().userCount());
        assertEquals(3, bounded.registry().sessionCount());
        assertTrue(bounded.registry().unregister(two));
        assertFalse(bounded.registry().unregister(two));
        assertCode(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE, () -> bounded.checkPlanning(two));
        assertTrue(bounded.registry().unregister(three));
        bounded.registry().register(fixture.session("125", "replacement", NOW + 300));
        assertEquals(2, bounded.registry().userCount());
        assertEquals(2, bounded.registry().sessionCount());
        assertEquals(0, fixture.reads.get());
    }

    @Test
    void registrationsRequireValidSessionAndMonitorAuthorityKeys() throws Exception {
        var fixture = new Fixture();
        var session = fixture.session("123", "one", NOW + 300);
        var foreignMonitor = new AuthorizationVersionMonitor(fixture.reader(), Map.of(KID, keyPair().getPublic()),
                fixture.clock);
        assertCode(AuthzErrorCode.AUTHENTICATION_FAILED, () -> foreignMonitor.registry().register(session));
        session.markUnverified();
        assertCode(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE, () -> fixture.monitor.registry().register(session));
        var expired = fixture.session("124", "expired", NOW + 300);
        fixture.clock.set(NOW + 30);
        assertCode(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE, () -> fixture.monitor.registry().register(expired));
        assertEquals(0, fixture.monitor.registry().sessionCount());
    }

    @Test
    void batchesStayWithinHundredUniqueUsersAndDoNotPollIdleExpiredRuns() throws Exception {
        var fixture = new Fixture();
        for (int i = 1; i <= 205; i++) {
            fixture.monitor.registry().register(fixture.session(Integer.toString(i), "run-" + i, NOW + 300));
        }
        fixture.clock.set(NOW + 10);
        assertEquals(205, fixture.monitor.reconcileBatch());
        assertEquals(List.of(100, 100, 5), fixture.requests.stream().map(List::size).toList());
        assertEquals(205, fixture.requests.stream().flatMap(List::stream).distinct().count());
        fixture.clock.set(NOW + 31);
        assertEquals(205, fixture.monitor.registry().sessionCount());
        assertEquals(0, fixture.monitor.registry().cleanup());
        fixture.clock.set(NOW + 300);
        assertEquals(0, fixture.monitor.reconcileBatch());
        assertEquals(0, fixture.monitor.registry().userCount());
        assertEquals(0, fixture.monitor.registry().sessionCount());
        assertEquals(3, fixture.reads.get());
    }

    @Test
    void blockedNetworkHoldsNoRegistryOrSessionLockAndParallelPlanningSharesOneRead() throws Exception {
        var fixture = new Fixture();
        var one = fixture.session("123", "one", NOW + 300);
        var two = fixture.session("123", "two", NOW + 300);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var joinerStarted = new CountDownLatch(1);
        var joinerThread = new AtomicReference<Thread>();
        var reads = new AtomicInteger();
        var monitor = new AuthorizationVersionMonitor(ids -> {
            reads.incrementAndGet();
            assertEquals(List.of("123"), ids);
            entered.countDown();
            await(release);
            return Map.of("123", one.freshness());
        }, Map.of(KID, fixture.key.getPublic()), fixture.clock);
        monitor.registry().register(one);
        monitor.registry().register(two);
        var pool = Executors.newFixedThreadPool(3);
        try {
            var first = pool.submit(() -> monitor.checkPlanning(one));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var second = pool.submit(() -> {
                joinerThread.set(Thread.currentThread());
                joinerStarted.countDown();
                return monitor.checkPlanning(two);
            });
            assertTrue(joinerStarted.await(2, TimeUnit.SECONDS));
            long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (joinerThread.get().getState() != Thread.State.WAITING && System.nanoTime() < waitUntil) {
                Thread.yield();
            }
            assertEquals(Thread.State.WAITING, joinerThread.get().getState());
            var notification = pool.submit(() -> {
                monitor.onNotification("123", 8, 0);
                assertEquals(AuthorizationLocalState.DIRTY, one.state());
                assertEquals(AuthorizationLocalState.DIRTY, two.state());
                assertEquals(0, monitor.reconcileBatch());
                var unrelated = fixture.session("124", "unrelated", NOW + 300);
                monitor.registry().register(unrelated);
                assertTrue(monitor.registry().unregister(unrelated));
                return true;
            });
            assertTrue(notification.get(2, TimeUnit.SECONDS));
            release.countDown();
            assertEquals(AuthorizationLocalState.DIRTY, first.get(2, TimeUnit.SECONDS));
            assertEquals(AuthorizationLocalState.DIRTY, second.get(2, TimeUnit.SECONDS));
            assertEquals(1, reads.get());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void inFlightOldUserGenerationCannotInvalidateNewRegistration() throws Exception {
        var fixture = new Fixture();
        var old = fixture.session("123", "old", NOW + 300);
        var replacement = fixture.session("123", "replacement", NOW + 300);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var monitor = new AuthorizationVersionMonitor(ids -> {
            entered.countDown();
            await(release);
            return Map.of("123", fixture.head("123", 99, 99, AuthorizationStatus.DELETED,
                    old.context().permissionHash(), NOW, NOW + 30));
        }, Map.of(KID, fixture.key.getPublic()), fixture.clock);
        monitor.registry().register(old);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var read = pool.submit(() -> monitor.checkPlanning(old));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(monitor.registry().unregister(old));
            monitor.registry().register(replacement);
            release.countDown();
            read.get(2, TimeUnit.SECONDS);
            assertEquals(AuthorizationLocalState.VALID, replacement.state());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void expiredButAuthenticHigherHeadStillTightensWithoutRenewing() throws Exception {
        var fixture = new Fixture();
        var session = fixture.session("123", "one", NOW + 300);
        fixture.monitor.registry().register(session);
        fixture.clock.set(NOW + 31);
        fixture.heads.set(Map.of("123", fixture.head("123", 8, 0, AuthorizationStatus.ACTIVE,
                session.context().permissionHash(), NOW, NOW + 30)));
        assertEquals(AuthorizationLocalState.DIRTY, fixture.monitor.checkPlanning(session));
        assertEquals(NOW + 30, session.effectiveDeadlineEpochSecond());
        assertCode(AuthzErrorCode.CONTEXT_CONFLICT, () -> session.requirePermission("memory.read"));
    }

    @Test
    void sameRevisionEpochChangeTightensWithoutInvalidatingDifferentUser() throws Exception {
        var fixture = new Fixture();
        var first = fixture.session("123", "one", NOW + 300);
        var other = fixture.session("124", "other", NOW + 300);
        fixture.monitor.registry().register(first);
        fixture.monitor.registry().register(other);
        fixture.clock.set(NOW + 1);
        fixture.heads.set(Map.of("123", fixture.head("123", 7, 1, AuthorizationStatus.ACTIVE,
                first.context().permissionHash(), NOW + 1, NOW + 31)));
        assertEquals(AuthorizationLocalState.DIRTY, fixture.monitor.checkPlanning(first));
        assertEquals(AuthorizationLocalState.VALID, other.state());
        assertEquals(0, first.context().credentialEpoch());
        assertEquals(NOW + 30, first.effectiveDeadlineEpochSecond());
    }

    @Test
    void backwardsMonitorClockNeverRenewsVerifiedAuthority() throws Exception {
        var fixture = new Fixture();
        var session = fixture.session("123", "one", NOW + 300);
        fixture.monitor.registry().register(session);
        fixture.clock.set(NOW + 10);
        fixture.monitor.reconcileBatch();
        fixture.clock.set(NOW + 5);
        assertEquals(0, fixture.monitor.reconcileBatch());
        assertEquals(AuthorizationLocalState.UNVERIFIED, session.state());
        fixture.clock.set(NOW + 11);
        assertEquals(AuthorizationLocalState.UNVERIFIED, fixture.monitor.checkPlanning(session));
        assertEquals(1, fixture.reads.get());
        assertCode(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE, () -> session.requirePermission("memory.read"));
    }

    private static final class Fixture {
        final KeyPair key;
        final FakeClock clock = new FakeClock();
        final AtomicInteger reads = new AtomicInteger();
        final AtomicReference<Map<String, AuthzFreshness>> heads = new AtomicReference<>(Map.of());
        final AtomicReference<RuntimeException> failure = new AtomicReference<>();
        final List<List<String>> requests = new ArrayList<>();
        final AuthorizationCache cache = new AuthorizationCache(512, 2_000_000, Duration.ofMinutes(1), 8);
        final AuthorizationVersionMonitor monitor;

        Fixture() throws Exception { this(keyPair()); }
        Fixture(KeyPair key) {
            this.key = key;
            monitor = new AuthorizationVersionMonitor(reader(), Map.of(KID, key.getPublic()), clock);
        }

        AuthorizationVersionMonitor.HeadReader reader() {
            return ids -> {
                reads.incrementAndGet();
                requests.add(List.copyOf(ids));
                assertThrows(UnsupportedOperationException.class, () -> ids.add("999"));
                if (failure.get() != null) throw failure.get();
                return heads.get();
            };
        }

        AuthorizationSession session(String actor, String run, long expires) {
            var snapshot = AuthzSignature.sign(new AuthorizationSnapshot(actor, 7, 3, false, PERMISSIONS, null,
                    AuthzContract.ALGORITHM, KID, null), key.getPrivate());
            String hash = AuthzCanonicalHash.snapshotHash(snapshot);
            var context = AuthzSignature.sign(new AuthorizationContext(actor, 7, 0, hash, PERMISSIONS,
                    "agent-service", "entry-request", run, "session-" + run, HASH, HASH, NONCE,
                    NOW, expires, NOW + 300, AuthzContract.ALGORITHM, KID, null), key.getPrivate());
            var freshness = head(actor, 7, 0, AuthorizationStatus.ACTIVE, hash, NOW, NOW + 30);
            if (heads.get() != null) {
                var next = new java.util.HashMap<>(heads.get());
                next.put(actor, freshness);
                heads.set(Map.copyOf(next));
            }
            return AuthorizationSession.fromEntry(new AuthzResponse(snapshot, context, freshness), "agent-service",
                    new AuthorizationSession.ResponseCorrelation("entry-request", HASH, NONCE), cache,
                    Map.of(KID, key.getPublic()), clock);
        }

        AuthzFreshness head(String actor, long revision, long epoch, AuthorizationStatus status, String hash,
                            long checked, long expires) {
            return signedHead(key, actor, revision, epoch, status, hash, checked, expires);
        }
    }

    private static AuthzFreshness signedHead(KeyPair key, String actor, long revision, long epoch,
                                            AuthorizationStatus status, String hash, long checked, long expires) {
        return AuthzSignature.sign(new AuthzFreshness(actor, revision, epoch, status, hash, checked, expires,
                NONCE, AuthzContract.ALGORITHM, KID, null), key.getPrivate());
    }

    private static void assertCode(AuthzErrorCode code, org.junit.jupiter.api.function.Executable executable) {
        assertEquals(code, assertThrows(AuthzException.class, executable).code());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted");
        }
    }

    private static final class FakeClock extends Clock {
        private final AtomicReference<Instant> value = new AtomicReference<>(Instant.ofEpochSecond(NOW));
        void set(long second) { value.set(Instant.ofEpochSecond(second)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return value.get(); }
    }
}
