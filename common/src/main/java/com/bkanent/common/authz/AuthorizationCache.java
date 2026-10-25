package com.bkanent.common.authz;

import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Local verified immutable snapshot cache. Entries, conservatively accounted content bytes, idle
 * residence and concurrent loads are bounded. Live session references are owned/bounded by the
 * consumer, not this cache. This cache neither authenticates new requests nor writes Redis.
 * Single-flight shares snapshots only, never per-run contexts, credentials or freshness.
 */
public final class AuthorizationCache {
    private final int maxEntries;
    private final long maxBytes;
    private final long idleNanos;
    private final int maxFlights;
    private final LongSupplier ticker;
    private final LinkedHashMap<AuthorizationKey, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<AuthorizationKey, CompletableFuture<AuthorizationSnapshot>> flights = new LinkedHashMap<>();
    private long accountedBytes;

    public AuthorizationCache(int maxEntries, long maxBytes, Duration idleTimeout, int maxFlights) {
        this(maxEntries, maxBytes, idleTimeout, maxFlights, System::nanoTime);
    }

    /** Ticker is a monotonic nanosecond source; injectable for deterministic idle tests. */
    public AuthorizationCache(int maxEntries, long maxBytes, Duration idleTimeout, int maxFlights,
                              LongSupplier ticker) {
        AuthzContract.require(maxEntries > 0 && maxEntries <= 100_000 && maxBytes > 0
                && idleTimeout != null && !idleTimeout.isNegative() && !idleTimeout.isZero()
                && idleTimeout.compareTo(Duration.ofDays(1)) <= 0
                && maxFlights > 0 && maxFlights <= maxEntries && ticker != null);
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
        this.idleNanos = idleTimeout.toNanos();
        this.maxFlights = maxFlights;
        this.ticker = ticker;
    }

    /** Admission always verifies authority signature and grant expiry, even for a cache hit. */
    public AuthorizationSnapshot intern(AuthorizationSnapshot snapshot, Map<String, PublicKey> authorityKeys,
                                         Instant now) {
        AuthzSignature.verifySnapshot(snapshot, authorityKeys, now);
        AuthorizationKey key = AuthorizationKey.of(snapshot);
        String hash = AuthzCanonicalHash.snapshotHash(snapshot);
        long bytes = accountedSize(snapshot);
        AuthzContract.check(bytes <= maxBytes, AuthzErrorCode.AUTHORIZATION_UNAVAILABLE);
        synchronized (this) {
            long tick = ticker.getAsLong();
            sweep(tick, now);
            Entry previous = entries.get(key);
            if (previous != null) {
                AuthzContract.check(previous.hash.equals(hash), AuthzErrorCode.CONTEXT_CONFLICT);
                if (previous.snapshot.equals(snapshot)) {
                    previous.lastAccess = tick;
                    return previous.snapshot;
                }
                // Key/signature rotation replaces the cached envelope, never its stable facts.
                remove(key);
            }
            while (entries.size() >= maxEntries || accountedBytes > maxBytes - bytes) {
                remove(entries.keySet().iterator().next());
            }
            entries.put(key, new Entry(snapshot, hash, bytes, tick));
            accountedBytes += bytes;
            return snapshot;
        }
    }

    /** A hit is stable data only, not a fresh entry credential or evidence of current authority. */
    public synchronized Optional<AuthorizationSnapshot> get(AuthorizationKey key, Instant now) {
        AuthzContract.require(key != null && now != null);
        long tick = ticker.getAsLong();
        sweep(tick, now);
        Entry value = entries.get(key);
        if (value == null) return Optional.empty();
        value.lastAccess = tick;
        return Optional.of(value.snapshot);
    }

    /** Supplier runs outside the monitor; failures are sanitized and never retained in the cache. */
    public AuthorizationSnapshot load(AuthorizationKey key, Supplier<AuthorizationSnapshot> loader,
                                       Map<String, PublicKey> authorityKeys, Clock clock) {
        AuthzContract.require(key != null && loader != null && clock != null);
        Instant now = clock.instant();
        CompletableFuture<AuthorizationSnapshot> flight;
        boolean owner = false;
        synchronized (this) {
            Optional<AuthorizationSnapshot> hit = get(key, now);
            if (hit.isPresent()) {
                AuthzSignature.verifySnapshot(hit.get(), authorityKeys, now);
                return hit.get();
            }
            flight = flights.get(key);
            if (flight == null) {
                AuthzContract.check(flights.size() < maxFlights, AuthzErrorCode.AUTHORIZATION_UNAVAILABLE);
                flight = new CompletableFuture<>();
                flights.put(key, flight);
                owner = true;
            }
        }
        if (owner) {
            try {
                AuthorizationSnapshot loaded = loader.get();
                AuthzContract.check(loaded != null && key.equals(AuthorizationKey.of(loaded)), AuthzErrorCode.CONTEXT_CONFLICT);
                flight.complete(intern(loaded, authorityKeys, clock.instant()));
            } catch (Throwable failure) {
                flight.completeExceptionally(failure instanceof AuthzException ? failure
                        : new AuthzException(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE));
                if (failure instanceof Error error) throw error;
            } finally {
                synchronized (this) { flights.remove(key, flight); }
            }
        }
        try {
            AuthorizationSnapshot result = flight.join();
            // Each waiter has its own trusted key registry; no sharing verifier authority.
            AuthzSignature.verifySnapshot(result, authorityKeys, clock.instant());
            return result;
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof AuthzException authz) throw authz;
            throw new AuthzException(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE);
        }
    }

    public synchronized void invalidate(AuthorizationKey key) {
        AuthzContract.require(key != null);
        remove(key);
        // In-flight results remain merely immutable data, not current permission grants.
    }

    public synchronized Stats stats(Instant now) {
        AuthzContract.require(now != null);
        sweep(ticker.getAsLong(), now);
        return new Stats(entries.size(), accountedBytes, flights.size());
    }

    /** Conservative content accounting, not a claim to measure JVM retained heap exactly. */
    public static long accountedSize(AuthorizationSnapshot snapshot) {
        AuthzContract.require(snapshot != null);
        long size = 512 + textBytes(snapshot.actorId()) + textBytes(snapshot.algorithm())
                + textBytes(snapshot.keyId()) + textBytes(snapshot.signature());
        for (String permission : snapshot.permissions()) size += 72 + textBytes(permission);
        return size;
    }

    private static long textBytes(String value) {
        return value == null ? 0 : 48L + 2L * value.length();
    }

    private void sweep(long tick, Instant now) {
        Iterator<Map.Entry<AuthorizationKey, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            Long grant = entry.snapshot.grantDeadlineEpochSecond();
            if (tick - entry.lastAccess >= idleNanos || (grant != null && now.getEpochSecond() >= grant)) {
                accountedBytes -= entry.bytes;
                iterator.remove();
            }
        }
    }

    private void remove(AuthorizationKey key) {
        Entry removed = entries.remove(key);
        if (removed != null) accountedBytes -= removed.bytes;
    }

    private static final class Entry {
        private final AuthorizationSnapshot snapshot;
        private final String hash;
        private final long bytes;
        private long lastAccess;

        private Entry(AuthorizationSnapshot snapshot, String hash, long bytes, long lastAccess) {
            this.snapshot = snapshot;
            this.hash = hash;
            this.bytes = bytes;
            this.lastAccess = lastAccess;
        }

        @Override public String toString() { return "AuthorizationCache.Entry[REDACTED]"; }
    }

    public record Stats(int entries, long accountedBytes, int inFlightLoads) {
        @Override public String toString() { return "AuthorizationCache.Stats[REDACTED]"; }
    }

    @Override public String toString() { return "AuthorizationCache[REDACTED]"; }
}
