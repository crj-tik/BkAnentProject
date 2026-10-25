package com.bkanent.common.authz;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.bkanent.common.authz.AuthzTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class AuthorizationCacheTest {
    @Test
    void verifiedImmutableSnapshotsAreSharedAndSameVersionDifferentFactsReject() throws Exception {
        var pair = keyPair();
        var keys = Map.of(KID, pair.getPublic());
        var cache = cache(4, 20_000, new AtomicLong());
        var snapshot = signed(pair, 7, null);
        assertSame(snapshot, cache.intern(snapshot, keys, now()));
        assertSame(snapshot, cache.intern(signed(pair, 7, null), keys, now()));
        assertSame(snapshot, cache.get(AuthorizationKey.of(snapshot), now()).orElseThrow());
        var changed = AuthzSignature.sign(new AuthorizationSnapshot("123", 7, 4, false, PERMISSIONS,
                null, AuthzContract.ALGORITHM, KID, null), pair.getPrivate());
        assertCode(AuthzErrorCode.CONTEXT_CONFLICT, () -> cache.intern(changed, keys, now()));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.permissions().clear());
        assertEquals("AuthorizationCache[REDACTED]", cache.toString());
        assertEquals("AuthorizationKey[REDACTED]", AuthorizationKey.of(snapshot).toString());
    }

    @Test
    void entryByteIdleAndGrantBoundsAreEnforcedWithoutChangingSnapshotHash() throws Exception {
        var pair = keyPair();
        var keys = Map.of(KID, pair.getPublic());
        var ticker = new AtomicLong();
        var cache = cache(2, 20_000, ticker);
        var first = signed(pair, 1, null);
        cache.intern(first, keys, now());
        cache.intern(signed(pair, 2, null), keys, now());
        cache.intern(signed(pair, 3, null), keys, now());
        assertEquals(2, cache.stats(now()).entries());
        assertTrue(cache.get(AuthorizationKey.of(first), now()).isEmpty());
        long size = AuthorizationCache.accountedSize(first);
        var bytes = cache(8, size + 1, new AtomicLong());
        bytes.intern(first, keys, now());
        bytes.intern(signed(pair, 2, null), keys, now());
        assertEquals(1, bytes.stats(now()).entries());
        assertTrue(bytes.stats(now()).accountedBytes() <= size + 1);
        var tiny = cache(1, size - 1, new AtomicLong());
        assertCode(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE, () -> tiny.intern(first, keys, now()));
        ticker.set(Duration.ofSeconds(10).toNanos());
        assertEquals(0, cache.stats(now()).entries());
        cache.intern(first, keys, now());
        assertEquals(AuthzCanonicalHash.snapshotHash(first), AuthzCanonicalHash.snapshotHash(cache.get(AuthorizationKey.of(first), now()).orElseThrow()));
        var grant = signed(pair, 4, NOW + 5);
        cache.intern(grant, keys, now());
        assertTrue(cache.get(AuthorizationKey.of(grant), Instant.ofEpochSecond(NOW + 5)).isEmpty());
    }

    @Test
    void singleFlightDoesNotHoldMonitorAndDifferentKeyLoadsAreBounded() throws Exception {
        var pair = keyPair();
        var keys = Map.of(KID, pair.getPublic());
        var cache = new AuthorizationCache(2, 20_000, Duration.ofSeconds(10), 1);
        var snapshot = signed(pair, 7, null);
        var key = AuthorizationKey.of(snapshot);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        var calls = new AtomicInteger();
        Clock clock = Clock.fixed(now(), ZoneOffset.UTC);
        try {
            var owner = pool.submit(() -> cache.load(key, () -> {
                calls.incrementAndGet(); entered.countDown(); await(release); return snapshot;
            }, keys, clock));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(1, cache.stats(now()).inFlightLoads());
            assertCode(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE, () -> cache.load(new AuthorizationKey("123", 8),
                    () -> signed(pair, 8, null), keys, clock));
            var waiterStarted = new CountDownLatch(1);
            var waiter = pool.submit(() -> {
                waiterStarted.countDown();
                return cache.load(key, () -> { calls.incrementAndGet(); return snapshot; }, keys, clock);
            });
            assertTrue(waiterStarted.await(2, TimeUnit.SECONDS));
            release.countDown();
            assertSame(owner.get(2, TimeUnit.SECONDS), waiter.get(2, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
            assertEquals(0, cache.stats(now()).inFlightLoads());
        } finally {
            release.countDown(); pool.shutdownNow();
        }
    }

    @Test
    void loadersVerifyKeySignatureAndCompletionTimeAndFailuresAreNotCached() throws Exception {
        var pair = keyPair();
        var keys = Map.of(KID, pair.getPublic());
        var cache = cache(4, 20_000, new AtomicLong());
        var snapshot = signed(pair, 7, null);
        var clock = new AuthorizationSessionTest.MutableClock(NOW);
        assertCode(AuthzErrorCode.CONTEXT_CONFLICT, () -> cache.load(new AuthorizationKey("123", 8), () -> snapshot, keys, clock));
        var wrongPair = keyPair();
        assertCode(AuthzErrorCode.AUTHENTICATION_FAILED, () -> cache.load(AuthorizationKey.of(snapshot),
                () -> signed(wrongPair, 7, null), keys, clock));
        var failure = assertThrows(AuthzException.class, () -> cache.load(AuthorizationKey.of(snapshot),
                () -> { throw new IllegalStateException("secret-token"); }, keys, clock));
        assertEquals(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE, failure.code());
        assertNull(failure.getCause());
        assertEquals(0, cache.stats(now()).inFlightLoads());
        var expiring = signed(pair, 8, NOW + 5);
        assertCode(AuthzErrorCode.EXPIRED, () -> cache.load(AuthorizationKey.of(expiring), () -> {
            clock.set(NOW + 5); return expiring;
        }, keys, clock));
        assertSame(snapshot, cache.load(AuthorizationKey.of(snapshot), () -> snapshot, keys, clock));
    }

    @Test
    void cacheNeverGrantsCurrentFreshnessAndUntrustedRegistriesCannotUseSharedLoads() throws Exception {
        var pair = keyPair();
        var keys = Map.of(KID, pair.getPublic());
        var cache = cache(4, 20_000, new AtomicLong());
        var snapshot = signed(pair, 7, null);
        cache.intern(snapshot, keys, now());
        var wrong = keyPair();
        assertCode(AuthzErrorCode.AUTHENTICATION_FAILED, () -> cache.load(AuthorizationKey.of(snapshot), () -> snapshot,
                Map.of(KID, wrong.getPublic()), Clock.fixed(now(), ZoneOffset.UTC)));
        assertTrue(cache.get(AuthorizationKey.of(snapshot), Instant.ofEpochSecond(NOW + 300)).isPresent());
        // Stable snapshot data survives in cache; an old run/freshness does not become valid.
        cache.invalidate(AuthorizationKey.of(snapshot));
        assertTrue(cache.get(AuthorizationKey.of(snapshot), now()).isEmpty());
    }

    @Test
    void concurrentInsertionsKeepStrictEntryAndContentBounds() throws Exception {
        var pair = keyPair();
        var keys = Map.of(KID, pair.getPublic());
        var cache = cache(4, 8_000, new AtomicLong());
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Future<?>> tasks = new ArrayList<>();
            for (int i = 1; i <= 40; i++) {
                final int revision = i;
                tasks.add(pool.submit(() -> {
                    cache.intern(signed(pair, revision, null), keys, now());
                    var stats = cache.stats(now());
                    assertTrue(stats.entries() <= 4);
                    assertTrue(stats.accountedBytes() <= 8_000);
                }));
            }
            for (var task : tasks) task.get(3, TimeUnit.SECONDS);
            assertTrue(cache.stats(now()).entries() <= 4);
            assertTrue(cache.stats(now()).accountedBytes() <= 8_000);
        } finally { pool.shutdownNow(); }
    }

    private static AuthorizationCache cache(int entries, long bytes, AtomicLong ticker) {
        return new AuthorizationCache(entries, bytes, Duration.ofSeconds(10), Math.min(entries, 2), ticker::get);
    }
    private static AuthorizationSnapshot signed(KeyPair pair, long revision, Long grant) {
        return AuthzSignature.sign(new AuthorizationSnapshot("123", revision, 3, false, PERMISSIONS, grant,
                AuthzContract.ALGORITHM, KID, null), pair.getPrivate());
    }
    private static Instant now() { return Instant.ofEpochSecond(NOW); }
    private static void assertCode(AuthzErrorCode code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(AuthzException.class, action).code());
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("timed out"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
    }
}
