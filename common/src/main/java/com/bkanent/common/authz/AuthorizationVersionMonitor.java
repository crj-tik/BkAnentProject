package com.bkanent.common.authz;

import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Framework-free consumer of Auth's signed user heads, not an authorization issuer. Register a
 * verified entry once; invoke checkPlanning only on business replanning and reconcileBatch from
 * an external scheduler at least every ten seconds. All action/output permission checks remain
 * AuthorizationSession's local checks. DIRTY/UNVERIFIED recovery and authenticated single-flight
 * context refresh belong to the consumer, never to this monitor. No credentials are retained.
 */
public final class AuthorizationVersionMonitor {
    public static final int MAX_BATCH_USERS = 100;
    public static final long RECONCILE_SECONDS = 10;
    public static final int DEFAULT_MAX_USERS = 1_000;
    public static final int DEFAULT_MAX_SESSIONS = 4_000;
    public static final int DEFAULT_MAX_SESSIONS_PER_USER = 32;

    /**
     * Read only the requested user heads in Auth's configured namespace. The protected Redis
     * adapter must decode the complete immutable AuthzFreshness proof, not unsigned hash fields.
     * A missing head may be omitted. Malformed wire data must throw an AuthzException other than
     * AUTHORIZATION_UNAVAILABLE; transport failure must use AUTHORIZATION_UNAVAILABLE or another
     * runtime exception. Neither missing heads nor transport failure renew authority freshness.
     */
    @FunctionalInterface
    public interface HeadReader {
        Map<String, AuthzFreshness> readHeads(List<String> users);
    }

    /** Mirrors RedisAuthorizationWriter.headKey; no Redis implementation/dependency lives here. */
    public static String headKey(String namespace, String userId) {
        AuthzContract.require(namespace != null && namespace.matches("[a-zA-Z0-9_-]{1,40}"));
        return namespace + ":{" + AuthzContract.actor(userId) + "}:head";
    }

    /** Only lossless user/version/epoch facts are consumed from the writer's notification. */
    public record VersionNotification(String userId, long permissionRevision, long credentialEpoch) {
        public VersionNotification {
            userId = AuthzContract.actor(userId);
            AuthzContract.require(permissionRevision > 0 && credentialEpoch >= 0);
        }
        @Override public String toString() { return "VersionNotification[REDACTED]"; }
    }

    private final Object lock = new Object();
    private final HeadReader reader;
    private final Map<String, PublicKey> authorityKeys;
    private final Clock clock;
    private final int maxUsers;
    private final int maxSessions;
    private final int maxSessionsPerUser;
    private final LinkedHashMap<String, UserRecord> users = new LinkedHashMap<>();
    private final Registry registry = new Registry();
    private int sessionCount;
    private long lastObservedSecond;
    private boolean clockRollback;

    public AuthorizationVersionMonitor(HeadReader reader, Map<String, PublicKey> authorityKeys, Clock clock) {
        this(reader, authorityKeys, clock, DEFAULT_MAX_USERS, DEFAULT_MAX_SESSIONS,
                DEFAULT_MAX_SESSIONS_PER_USER);
    }

    public AuthorizationVersionMonitor(HeadReader reader, Map<String, PublicKey> authorityKeys, Clock clock,
                                       int maxUsers, int maxSessions, int maxSessionsPerUser) {
        AuthzContract.require(reader != null && authorityKeys != null && !authorityKeys.isEmpty()
                && clock != null && maxUsers > 0 && maxSessions > 0 && maxSessionsPerUser > 0);
        this.reader = reader;
        this.authorityKeys = Map.copyOf(authorityKeys);
        this.clock = clock;
        this.maxUsers = maxUsers;
        this.maxSessions = maxSessions;
        this.maxSessionsPerUser = maxSessionsPerUser;
    }

    public Registry registry() { return registry; }

    /** Identity-based registrations cannot overwrite another run or evict a live registration. */
    public final class Registry {
        private Registry() { }

        public void register(AuthorizationSession session) {
            AuthzContract.require(session != null);
            synchronized (lock) {
                Instant now = nowLocked();
                cleanupLocked(now.getEpochSecond());
                synchronized (session) {
                    AuthorizationContext context = session.context();
                    UserRecord existing = users.get(context.actorId());
                    if (existing != null && existing.sessions.containsKey(session)) return;
                    AuthzContract.check(!clockRollback, AuthzErrorCode.AUTHORIZATION_UNAVAILABLE);
                    AuthzContract.check(session.state() == AuthorizationLocalState.VALID,
                            AuthzErrorCode.AUTHORIZATION_UNAVAILABLE);
                    // A session verified against another authority key map is not sufficient.
                    AuthzSignature.verifySnapshot(session.snapshot(), authorityKeys, now);
                    AuthzSignature.verifyContext(context, context.audience(), authorityKeys, now);
                    AuthzSignature.verifyFreshness(session.freshness(), authorityKeys, now);
                    new AuthzResponse(session.snapshot(), context, session.freshness());
                    AuthzContract.check(sessionCount < maxSessions
                            && (existing != null || users.size() < maxUsers)
                            && (existing == null || existing.sessions.size() < maxSessionsPerUser),
                            AuthzErrorCode.AUTHORIZATION_UNAVAILABLE);
                    UserRecord user = existing == null ? new UserRecord(context.actorId(), now.getEpochSecond()) : existing;
                    if (existing == null) users.put(user.userId, user);
                    user.sessions.put(session, stableDeadline(session));
                    sessionCount++;
                    observeContextsLocked(user);
                    // Entry proof seeds monotonic records, but cannot erase an earlier notice.
                    consumeLocked(user, session.freshness(), now);
                    tightenLocked(user);
                }
            }
        }

        public boolean unregister(AuthorizationSession session) {
            if (session == null) return false;
            synchronized (lock) {
                UserRecord user = users.get(session.context().actorId());
                if (user == null || user.sessions.remove(session) == null) return false;
                sessionCount--;
                if (user.sessions.isEmpty()) users.remove(user.userId);
                return true;
            }
        }

        public int userCount() { synchronized (lock) { return users.size(); } }
        public int sessionCount() { synchronized (lock) { return sessionCount; } }

        /** Remove idle registrations at their original stable deadlines, not their 30s freshness. */
        public int cleanup() {
            synchronized (lock) { return cleanupLocked(nowLocked().getEpochSecond()); }
        }

        @Override public String toString() { return "AuthorizationRegistry[REDACTED]"; }
    }

    public void onNotification(VersionNotification event) {
        AuthzContract.require(event != null);
        synchronized (lock) {
            UserRecord user = users.get(event.userId());
            if (user == null) return;
            user.highestRevision = Math.max(user.highestRevision, event.permissionRevision());
            user.highestEpoch = Math.max(user.highestEpoch, event.credentialEpoch());
            tightenLocked(user);
        }
    }

    public void onNotification(String userId, long revision, long epoch) {
        onNotification(new VersionNotification(userId, revision, epoch));
    }

    /** One user head read (single-flight for parallel sessions), no snapshot download/Auth fetch. */
    public AuthorizationLocalState checkPlanning(AuthorizationSession session) {
        AuthzContract.require(session != null);
        ReadFlight flight;
        boolean owner;
        synchronized (lock) {
            Instant now = nowLocked();
            cleanupLocked(now.getEpochSecond());
            UserRecord user = users.get(session.context().actorId());
            AuthzContract.check(user != null && user.sessions.containsKey(session),
                    AuthzErrorCode.AUTHORIZATION_UNAVAILABLE);
            observeContextsLocked(user);
            if (clockRollback) return session.state();
            owner = user.flight == null;
            if (owner) startFlightLocked(user, now.getEpochSecond());
            flight = new ReadFlight(user, user.flight);
        }
        if (owner) readOutsideLock(List.of(flight));
        else flight.completion.join();
        return session.state();
    }

    /** Returns the number of unique users read; every network call contains at most 100 users. */
    public int reconcileBatch() {
        List<ReadFlight> due = new ArrayList<>();
        synchronized (lock) {
            long now = nowLocked().getEpochSecond();
            cleanupLocked(now);
            if (clockRollback) return 0;
            for (UserRecord user : users.values()) {
                observeContextsLocked(user);
                if (user.flight == null && now >= user.nextReconcileSecond) {
                    startFlightLocked(user, now);
                    due.add(new ReadFlight(user, user.flight));
                }
            }
        }
        try {
            for (int offset = 0; offset < due.size(); offset += MAX_BATCH_USERS) {
                readOutsideLock(due.subList(offset, Math.min(offset + MAX_BATCH_USERS, due.size())));
            }
            return due.size();
        } finally {
            // Even a fatal adapter error must release reservations for later batches.
            finishFlights(due);
        }
    }

    /** The scheduler must use the same injected clock as planning and deadline verification. */
    public int reconcileBatch(Clock schedulerClock) {
        AuthzContract.require(schedulerClock == clock);
        return reconcileBatch();
    }

    private void startFlightLocked(UserRecord user, long now) {
        user.flight = new CompletableFuture<>();
        user.nextReconcileSecond = now + RECONCILE_SECONDS;
    }

    private void readOutsideLock(List<ReadFlight> flights) {
        List<String> requested = flights.stream().map(flight -> flight.user.userId).toList();
        Map<String, AuthzFreshness> heads = null;
        boolean malformed = false;
        try {
            heads = reader.readHeads(requested);
            malformed = heads == null;
        } catch (AuthzException failure) {
            malformed = failure.code() != AuthzErrorCode.AUTHORIZATION_UNAVAILABLE;
        } catch (RuntimeException transportFailure) {
            // Keep only the already verified remaining window. Never use read time as checkedAt.
        } catch (Error failure) {
            finishFlights(flights);
            throw failure;
        }
        try {
            synchronized (lock) {
                Instant now = nowLocked();
                for (ReadFlight flight : flights) {
                    UserRecord user = flight.user;
                    // In-flight responses cannot update an unregistered/re-registered user generation.
                    if (users.get(user.userId) != user) continue;
                    observeContextsLocked(user);
                    if (malformed) markUnverifiedLocked(user);
                    else if (heads != null) {
                        try {
                            // Ignore foreign response keys; a foreign actor under our key is invalid.
                            if (heads.containsKey(user.userId)) consumeLocked(user, heads.get(user.userId), now);
                        } catch (RuntimeException malformedResponse) {
                            markUnverifiedLocked(user);
                        }
                    }
                }
            }
        } finally {
            finishFlights(flights);
        }
    }

    private void finishFlights(List<ReadFlight> flights) {
        synchronized (lock) {
            for (ReadFlight flight : flights) {
                if (flight.user.flight == flight.completion) flight.user.flight = null;
            }
        }
        // Completion may execute caller continuations: never complete while holding the registry lock.
        for (ReadFlight flight : flights) flight.completion.complete(null);
    }

    private void consumeLocked(UserRecord user, AuthzFreshness head, Instant now) {
        if (head == null || !user.userId.equals(head.userId())) {
            markUnverifiedLocked(user);
            return;
        }
        boolean expired = false;
        try {
            AuthzSignature.verifyFreshnessAttestation(head, authorityKeys, now);
        } catch (AuthzException failure) {
            if (failure.code() != AuthzErrorCode.EXPIRED) {
                markUnverifiedLocked(user);
                return;
            }
            // EXPIRED is emitted only after signature and checkedAt verification. Its signed
            // version can still invalidate an older run, but cannot renew any remaining window.
            expired = true;
        }
        // Unsigned notice watermarks can only tighten; signed old heads cannot erase them.
        if (head.permissionRevision() < user.highestRevision || head.credentialEpoch() < user.highestEpoch) return;
        user.highestRevision = Math.max(user.highestRevision, head.permissionRevision());
        user.highestEpoch = Math.max(user.highestEpoch, head.credentialEpoch());
        tightenLocked(user);
        AuthzFreshness old = user.head;
        if (old != null) {
            if (head.permissionRevision() == old.permissionRevision()
                    && (!head.permissionHash().equals(old.permissionHash()) || head.status() != old.status()
                    || head.credentialEpoch() != old.credentialEpoch())) {
                markUnverifiedLocked(user);
                return;
            }
            if (head.checkedAtEpochSecond() < old.checkedAtEpochSecond()) return;
            if (head.checkedAtEpochSecond() == old.checkedAtEpochSecond()
                    && head.permissionRevision() == old.permissionRevision()
                    && head.notAfterEpochSecond() != old.notAfterEpochSecond()) {
                markUnverifiedLocked(user);
                return;
            }
        }
        user.head = head;
        if (head.status() != AuthorizationStatus.ACTIVE) {
            markUnverifiedLocked(user);
            return;
        }
        if (expired) return;
        for (AuthorizationSession session : user.sessions.keySet()) {
            synchronized (session) {
                AuthorizationContext context = session.context();
                if (head.permissionRevision() != context.permissionRevision()
                        || head.credentialEpoch() != context.credentialEpoch()) continue;
                if (!head.permissionHash().equals(context.permissionHash())) {
                    session.markUnverified();
                    continue;
                }
                AuthzFreshness current = session.freshness();
                if (head.checkedAtEpochSecond() < current.checkedAtEpochSecond()) continue;
                if (head.checkedAtEpochSecond() == current.checkedAtEpochSecond()) {
                    if (head.notAfterEpochSecond() != current.notAfterEpochSecond()) session.markUnverified();
                    continue; // Identical authority facts must not call strict updateFreshness.
                }
                try {
                    session.updateFreshness(head);
                } catch (AuthzException failure) {
                    if (failure.code() != AuthzErrorCode.EXPIRED) session.markUnverified();
                }
            }
        }
    }

    private void observeContextsLocked(UserRecord user) {
        for (AuthorizationSession session : user.sessions.keySet()) {
            AuthorizationContext context = session.context();
            user.highestRevision = Math.max(user.highestRevision, context.permissionRevision());
            user.highestEpoch = Math.max(user.highestEpoch, context.credentialEpoch());
        }
        tightenLocked(user);
    }

    private void tightenLocked(UserRecord user) {
        for (AuthorizationSession session : user.sessions.keySet()) {
            session.onNotification(user.userId, user.highestRevision, user.highestEpoch);
        }
    }

    private void markUnverifiedLocked(UserRecord user) {
        for (AuthorizationSession session : user.sessions.keySet()) session.markUnverified();
    }

    private int cleanupLocked(long now) {
        int before = sessionCount;
        var iterator = users.values().iterator();
        while (iterator.hasNext()) {
            UserRecord user = iterator.next();
            var sessions = user.sessions.entrySet().iterator();
            while (sessions.hasNext()) {
                var entry = sessions.next();
                if (now >= Math.min(entry.getValue(), stableDeadline(entry.getKey()))) {
                    sessions.remove();
                    sessionCount--;
                }
            }
            if (user.sessions.isEmpty()) iterator.remove();
        }
        return before - sessionCount;
    }

    private Instant nowLocked() {
        Instant now = clock.instant();
        if (now.getEpochSecond() < lastObservedSecond) clockRollback = true;
        lastObservedSecond = Math.max(lastObservedSecond, now.getEpochSecond());
        if (clockRollback) for (UserRecord user : users.values()) markUnverifiedLocked(user);
        return Instant.ofEpochSecond(lastObservedSecond);
    }

    private static long stableDeadline(AuthorizationSession session) {
        synchronized (session) {
            AuthorizationContext context = session.context();
            long deadline = Math.min(context.expiresAtEpochSecond(), context.runHardDeadlineEpochSecond());
            Long grantDeadline = session.snapshot().grantDeadlineEpochSecond();
            return grantDeadline == null ? deadline : Math.min(deadline, grantDeadline);
        }
    }

    private static final class UserRecord {
        final String userId;
        final IdentityHashMap<AuthorizationSession, Long> sessions = new IdentityHashMap<>();
        long highestRevision;
        long highestEpoch;
        long nextReconcileSecond;
        AuthzFreshness head;
        CompletableFuture<Void> flight;

        UserRecord(String userId, long now) {
            this.userId = userId;
            this.nextReconcileSecond = now + RECONCILE_SECONDS;
        }
    }

    private record ReadFlight(UserRecord user, CompletableFuture<Void> completion) { }

    @Override public String toString() { return "AuthorizationVersionMonitor[REDACTED]"; }
}
