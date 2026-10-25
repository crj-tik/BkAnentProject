package com.bkanent.common.authz;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.bkanent.common.authz.AuthzTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class AuthorizationSessionTest {
    @Test
    void lostNotificationsExpireLocallyAndRenewalNeverSlidesOriginalContext() throws Exception {
        var key = keyPair();
        var clock = new MutableClock(NOW);
        var response = response(key, 7, 0, PERMISSIONS, "run-1", "session-1", NOW, NOW + 300, NOW + 300);
        var session = session(response, key, clock, cache());
        assertEquals(AuthorizationLocalState.VALID, session.state());
        clock.set(NOW + 30);
        assertEquals(AuthorizationLocalState.EXPIRED, session.state());
        assertCode(AuthzErrorCode.EXPIRED, () -> session.requirePermission("memory.read"));
        session.updateFreshness(fresh(key, response, NOW + 30));
        assertEquals(AuthorizationLocalState.VALID, session.state());
        assertEquals(NOW + 300, session.context().expiresAtEpochSecond());
        clock.set(NOW + 290);
        session.updateFreshness(fresh(key, response, NOW + 290));
        assertEquals(NOW + 300, session.effectiveDeadlineEpochSecond());
        clock.set(NOW + 300);
        assertEquals(AuthorizationLocalState.EXPIRED, session.state());
        assertCode(AuthzErrorCode.EXPIRED, () -> session.updateFreshness(fresh(key, response, NOW + 300)));
    }

    @Test
    void notificationsOnlyDirtyAndVersionWatermarksNeverRegressOrGrant() throws Exception {
        var key = keyPair();
        var clock = new MutableClock(NOW);
        var base = response(key, 7, 2, PERMISSIONS, "run-1", "session-1", NOW, NOW + 300, NOW + 300);
        var session = session(base, key, clock, cache());
        session.onNotification("124", 99, 99);
        session.onNotification("123", 6, 1);
        session.onNotification("123", 7, 2);
        assertEquals(AuthorizationLocalState.VALID, session.state());
        session.onNotification("123", 8, 2);
        session.onNotification("123", 7, 3);
        assertEquals(AuthorizationLocalState.DIRTY, session.state());
        assertCode(AuthzErrorCode.CONTEXT_CONFLICT, () -> session.requirePermission("memory.read"));
        clock.set(NOW + 1);
        session.updateFreshness(fresh(key, base, NOW + 1));
        assertEquals(AuthorizationLocalState.DIRTY, session.state());
        var behind = response(key, 8, 2, PERMISSIONS, "run-1", "session-1", NOW + 1, NOW + 300, NOW + 300);
        assertCode(AuthzErrorCode.CONTEXT_CONFLICT, () -> session.refresh(behind, correlation(behind)));
        var current = response(key, 8, 3, PERMISSIONS, "run-1", "session-1", NOW + 1, NOW + 300, NOW + 300);
        session.refresh(current, correlation(current));
        session.onNotification("123", 7, 2);
        assertEquals(AuthorizationLocalState.VALID, session.state());
        assertEquals(8, session.key().permissionRevision());
    }

    @Test
    void freshnessMustBeSignedCorrelatedAndStrictlyNewer() throws Exception {
        var key = keyPair();
        var clock = new MutableClock(NOW);
        var base = response(key, 7, 0, PERMISSIONS, "run-1", "session-1", NOW, NOW + 300, NOW + 300);
        var session = session(base, key, clock, cache());
        clock.set(NOW + 1);
        assertCode(AuthzErrorCode.CONTEXT_CONFLICT, () -> session.updateFreshness(base.freshness()));
        for (AuthzFreshness bad : List.of(
                new AuthzFreshness("124", 7, 0, AuthorizationStatus.ACTIVE, base.context().permissionHash(), NOW + 1, NOW + 31, NONCE, AuthzContract.ALGORITHM, KID, null),
                new AuthzFreshness("123", 8, 0, AuthorizationStatus.ACTIVE, base.context().permissionHash(), NOW + 1, NOW + 31, NONCE, AuthzContract.ALGORITHM, KID, null),
                new AuthzFreshness("123", 7, 1, AuthorizationStatus.ACTIVE, base.context().permissionHash(), NOW + 1, NOW + 31, NONCE, AuthzContract.ALGORITHM, KID, null),
                new AuthzFreshness("123", 7, 0, AuthorizationStatus.ACTIVE, HASH, NOW + 1, NOW + 31, NONCE, AuthzContract.ALGORITHM, KID, null))) {
            var signed = AuthzSignature.sign(bad, key.getPrivate());
            assertCode(AuthzErrorCode.CONTEXT_CONFLICT, () -> session.updateFreshness(signed));
        }
        var wrongKey = keyPair();
        assertCode(AuthzErrorCode.AUTHENTICATION_FAILED, () -> session.updateFreshness(fresh(wrongKey, base, NOW + 1)));
        session.markUnverified();
        session.updateFreshness(fresh(key, base, NOW + 1));
        assertEquals(AuthorizationLocalState.UNVERIFIED, session.state());
        session.refresh(response(key, 7, 0, PERMISSIONS, "run-1", "session-1", NOW + 1, NOW + 300, NOW + 300), correlation(base));
        assertEquals(AuthorizationLocalState.VALID, session.state());
    }

    @Test
    void sessionsShareSnapshotsNotRunIdentityStateOrPolicyAndRefreshIntersectionIsImmutable() throws Exception {
        var key = keyPair();
        var clock = new MutableClock(NOW);
        var cache = cache();
        var first = session(response(key, 7, 0, List.of("memory.read"), "run-1", "session-1", NOW, NOW + 300, NOW + 300), key, clock, cache);
        var second = session(response(key, 7, 0, List.of("memory.read"), "run-2", "session-2", NOW, NOW + 300, NOW + 300), key, clock, cache);
        assertSame(first.snapshot(), second.snapshot());
        assertNotEquals(first.context().runId(), second.context().runId());
        first.onNotification("123", 8, 0);
        assertEquals(AuthorizationLocalState.DIRTY, first.state());
        assertEquals(AuthorizationLocalState.VALID, second.state());
        clock.set(NOW + 1);
        var expanded = response(key, 8, 0, PERMISSIONS, "run-1", "session-1", NOW + 1, NOW + 300, NOW + 300);
        assertCode(AuthzErrorCode.PERMISSION_DENIED, () -> first.refresh(expanded, correlation(expanded)));
        var scoped = response(key, 8, 0, List.of("memory.read"), "run-1", "session-1", NOW + 1, NOW + 300, NOW + 300);
        first.refresh(scoped, correlation(scoped));
        assertEquals(List.of("memory.read"), first.allowedOperations());
        assertCode(AuthzErrorCode.PERMISSION_DENIED, () -> first.requirePermission("interview.read"));
        assertThrows(UnsupportedOperationException.class, () -> first.allowedOperations().clear());
        assertFalse(java.io.Serializable.class.isAssignableFrom(first.getClass()));
        assertEquals("AuthorizationSession[REDACTED]", first.toString());
        assertEquals(AuthorizationLocalState.VALID, second.state());
    }

    @Test
    void refreshCannotReplaceActorRunSessionPolicyAudienceOrFixedDeadlines() throws Exception {
        var key = keyPair();
        var clock = new MutableClock(NOW + 1);
        var base = response(key, 7, 0, PERMISSIONS, "run-1", "session-1", NOW, NOW + 100, NOW + 300);
        var session = session(base, key, clock, cache());
        for (AuthzResponse bad : List.of(
                response(key, 8, 0, PERMISSIONS, "other-run", "session-1", NOW + 1, NOW + 100, NOW + 300),
                response(key, 8, 0, PERMISSIONS, "run-1", "other-session", NOW + 1, NOW + 100, NOW + 300),
                response(key, 8, 0, PERMISSIONS, "run-1", "session-1", NOW + 1, NOW + 101, NOW + 300),
                response(key, 8, 0, PERMISSIONS, "run-1", "session-1", NOW + 1, NOW + 100, NOW + 301))) {
            assertCode(AuthzErrorCode.CONTEXT_CONFLICT, () -> session.refresh(bad, correlation(bad)));
        }
        var valid = response(key, 8, 0, PERMISSIONS, "run-1", "session-1", NOW + 1, NOW + 100, NOW + 300);
        var otherSnapshot = AuthzSignature.sign(new AuthorizationSnapshot("124", 8, 3, false, PERMISSIONS, null,
                AuthzContract.ALGORITHM, KID, null), key.getPrivate());
        var otherHash = AuthzCanonicalHash.snapshotHash(otherSnapshot);
        var otherContext = AuthzSignature.sign(new AuthorizationContext("124", 8, 0, otherHash, PERMISSIONS,
                "agent-service", "entry-request", "run-1", "session-1", HASH, HASH, NONCE, NOW + 1,
                NOW + 100, NOW + 300, AuthzContract.ALGORITHM, KID, null), key.getPrivate());
        var otherFresh = AuthzSignature.sign(new AuthzFreshness("124", 8, 0, AuthorizationStatus.ACTIVE,
                otherHash, NOW + 1, NOW + 31, NONCE, AuthzContract.ALGORITHM, KID, null), key.getPrivate());
        var otherActor = new AuthzResponse(otherSnapshot, otherContext, otherFresh);
        assertCode(AuthzErrorCode.CONTEXT_CONFLICT, () -> session.refresh(otherActor, correlation(otherActor)));
        var c = valid.context();
        AuthorizationContext policy = new AuthorizationContext(c.actorId(), c.permissionRevision(), c.credentialEpoch(), c.permissionHash(),
                c.allowedOperations(), c.audience(), c.requestId(), c.runId(), c.sessionId(), "a".repeat(64), HASH, NONCE,
                c.issuedAtEpochSecond(), c.expiresAtEpochSecond(), c.runHardDeadlineEpochSecond(), c.algorithm(), KID, null);
        var badPolicy = new AuthzResponse(valid.snapshot(), AuthzSignature.sign(policy, key.getPrivate()), valid.freshness());
        assertCode(AuthzErrorCode.CONTEXT_CONFLICT, () -> session.refresh(badPolicy, correlation(badPolicy)));
        session.refresh(valid, correlation(valid));
        clock.set(NOW + 100);
        assertCode(AuthzErrorCode.EXPIRED, () -> session.refresh(valid, correlation(valid)));
    }

    @Test
    void childSessionRequiresVerifiedParentScopeAndSignedFreshness() throws Exception {
        var key = keyPair();
        var clock = new MutableClock(NOW);
        var parent = response(key, 7, 0, PERMISSIONS, "run-1", "session-1", NOW, NOW + 300, NOW + 300);
        var binding = binding();
        String hash = AuthzCanonicalHash.delegationRequestHash(parent.context(), binding);
        var context = AuthzSignature.sign(new AuthorizationContext("123", 7, 0, parent.context().permissionHash(),
                binding.operations(), binding.audience(), binding.requestId(), binding.runId(), "session-1", HASH, hash,
                NONCE, NOW, NOW + 300, NOW + 300, AuthzContract.ALGORITHM, KID, null), key.getPrivate());
        var childResponse = new AuthzResponse(parent.snapshot(), context, parent.freshness());
        var child = AuthorizationSession.fromChild(childResponse, parent.context(), binding, cache(), Map.of(KID, key.getPublic()), clock);
        child.requirePermission("memory.read");
        assertCode(AuthzErrorCode.PERMISSION_DENIED, () -> child.requirePermission("interview.read"));
        var wrongKey = keyPair();
        var invalidFreshness = new AuthzResponse(childResponse.snapshot(), childResponse.context(), fresh(wrongKey, childResponse, NOW));
        assertCode(AuthzErrorCode.AUTHENTICATION_FAILED, () -> AuthorizationSession.fromChild(invalidFreshness,
                parent.context(), binding, cache(), Map.of(KID, key.getPublic()), clock));
    }

    @Test
    void refreshSingleFlightIsPerSessionAndSanitizesFailure() throws Exception {
        var key = keyPair();
        var clock = new MutableClock(NOW);
        var response = response(key, 7, 0, PERMISSIONS, "run-1", "session-1", NOW, NOW + 300, NOW + 300);
        var session = session(response, key, clock, cache());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> session.refreshSingleFlight(correlation(response), () -> {
                calls.incrementAndGet(); entered.countDown(); await(release); return response;
            }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertCode(AuthzErrorCode.CONTEXT_CONFLICT, () -> session.refreshSingleFlight(
                    new AuthorizationSession.ResponseCorrelation("different", HASH, NONCE), () -> response));
            release.countDown();
            first.get(2, TimeUnit.SECONDS);
            assertEquals(1, calls.get());
            var failure = assertThrows(AuthzException.class, () -> session.refreshSingleFlight(correlation(response),
                    () -> { throw new IllegalStateException("secret-token"); }));
            assertEquals(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE, failure.code());
            assertNull(failure.getCause());
            assertFalse(failure.getMessage().contains("secret"));
            assertEquals(AuthorizationLocalState.VALID, session.state());
        } finally {
            release.countDown(); pool.shutdownNow();
        }
    }

    @Test
    void wallClockRollbackFailsClosedInsteadOfExtendingFreshness() throws Exception {
        var key = keyPair();
        var clock = new MutableClock(NOW);
        var response = response(key, 7, 0, PERMISSIONS, "run-1", "session-1", NOW, NOW + 300, NOW + 300);
        var session = session(response, key, clock, cache());
        clock.set(NOW + 10); session.state();
        clock.set(NOW + 5);
        assertEquals(AuthorizationLocalState.UNVERIFIED, session.state());
        clock.set(NOW + 11);
        assertCode(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE, () -> session.updateFreshness(fresh(key, response, NOW + 11)));
    }

    static AuthzResponse response(KeyPair key, long revision, long epoch, List<String> scope, String run,
                                  String session, long issued, long expires, long hard) {
        var snapshot = AuthzSignature.sign(new AuthorizationSnapshot("123", revision, 3, false, PERMISSIONS, null,
                AuthzContract.ALGORITHM, KID, null), key.getPrivate());
        var hash = AuthzCanonicalHash.snapshotHash(snapshot);
        var context = AuthzSignature.sign(new AuthorizationContext("123", revision, epoch, hash, scope,
                "agent-service", "entry-request", run, session, HASH, HASH, NONCE, issued, expires, hard,
                AuthzContract.ALGORITHM, KID, null), key.getPrivate());
        var fresh = AuthzSignature.sign(new AuthzFreshness("123", revision, epoch, AuthorizationStatus.ACTIVE,
                hash, issued, issued + 30, NONCE, AuthzContract.ALGORITHM, KID, null), key.getPrivate());
        return new AuthzResponse(snapshot, context, fresh);
    }

    private static AuthzFreshness fresh(KeyPair key, AuthzResponse response, long checked) {
        return AuthzSignature.sign(new AuthzFreshness("123", response.context().permissionRevision(),
                response.context().credentialEpoch(), AuthorizationStatus.ACTIVE, response.context().permissionHash(),
                checked, checked + 30, NONCE, AuthzContract.ALGORITHM, KID, null), key.getPrivate());
    }

    static AuthorizationSession.ResponseCorrelation correlation(AuthzResponse response) {
        return new AuthorizationSession.ResponseCorrelation(response.context().requestId(), response.context().requestHash(), response.context().nonce());
    }

    private static AuthorizationSession session(AuthzResponse response, KeyPair key, Clock clock, AuthorizationCache cache) {
        return AuthorizationSession.fromEntry(response, "agent-service", correlation(response), cache, Map.of(KID, key.getPublic()), clock);
    }

    private static AuthorizationCache cache() { return new AuthorizationCache(16, 64_000, Duration.ofMinutes(1), 4); }
    private static void assertCode(AuthzErrorCode code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(AuthzException.class, action).code());
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("timed out"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        MutableClock(long second) { instant = new AtomicReference<>(Instant.ofEpochSecond(second)); }
        void set(long second) { instant.set(Instant.ofEpochSecond(second)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant.get(); }
    }
}
