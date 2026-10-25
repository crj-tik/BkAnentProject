package com.bkanent.common.authz;

import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * An in-memory verified run reference, never Serializable or recoverable from identifiers alone.
 * Different runs share only cache snapshots, never this mutable local state. No remote I/O occurs
 * in permission/state checks. Resource, approval, callback, lease and source checks remain external.
 * The signed entry scope is a conservative original-policy ceiling; refresh never expands it.
 */
public final class AuthorizationSession {
    private final AuthorizationCache cache;
    private final Map<String, PublicKey> authorityKeys;
    private final Clock clock;
    private final String actorId;
    private final String runId;
    private final String sessionId;
    private final String audience;
    private final String policyDigest;
    private final long runHardDeadline;
    private final List<String> originalPolicyOperations;
    private AuthorizationSnapshot snapshot;
    private AuthorizationContext context;
    private AuthzFreshness freshness;
    private List<String> allowedOperations;
    private long originalDeadline;
    private long highestRevision;
    private long highestEpoch;
    private boolean dirty;
    private boolean unverified;
    private boolean clockRollback;
    private long lastObservedSecond;
    private CompletableFuture<Void> refreshFlight;
    private ResponseCorrelation refreshCorrelation;

    private AuthorizationSession(AuthzResponse response, String audience, ResponseCorrelation expected,
                                 AuthorizationCache cache, Map<String, PublicKey> keys, Clock clock) {
        AuthzContract.require(cache != null && keys != null && clock != null && expected != null);
        this.authorityKeys = Map.copyOf(keys);
        this.clock = clock;
        this.cache = cache;
        Instant now = clock.instant();
        verify(response, audience, expected, now);
        this.context = response.context();
        this.freshness = response.freshness();
        this.snapshot = cache.intern(response.snapshot(), authorityKeys, now);
        this.actorId = context.actorId();
        this.runId = context.runId();
        this.sessionId = context.sessionId();
        this.audience = context.audience();
        this.policyDigest = context.policyDigest();
        this.runHardDeadline = context.runHardDeadlineEpochSecond();
        this.originalPolicyOperations = context.allowedOperations();
        this.allowedOperations = context.allowedOperations();
        this.originalDeadline = stableDeadline(context, snapshot);
        this.highestRevision = context.permissionRevision();
        this.highestEpoch = context.credentialEpoch();
        this.lastObservedSecond = now.getEpochSecond();
    }

    /** Must only be called for a genuinely authenticated, fully request-correlated entry response. */
    public static AuthorizationSession fromEntry(AuthzResponse response, String expectedAudience,
                                                 ResponseCorrelation expected, AuthorizationCache cache,
                                                 Map<String, PublicKey> authorityKeys, Clock clock) {
        return new AuthorizationSession(response, expectedAudience, expected, cache, authorityKeys, clock);
    }

    /** Child creation additionally verifies the signed parent scope and exact delegation binding. */
    public static AuthorizationSession fromChild(AuthzResponse response, AuthorizationContext parent,
                                                 DelegationBinding binding, AuthorizationCache cache,
                                                 Map<String, PublicKey> authorityKeys, Clock clock) {
        AuthzContract.require(clock != null && binding != null && response != null);
        ResponseCorrelation expected = new ResponseCorrelation(binding.requestId(),
                AuthzCanonicalHash.delegationRequestHash(parent, binding), binding.nonce());
        AuthzSignature.verifyChildContext(parent, response.context(), binding, authorityKeys, clock.instant());
        return new AuthorizationSession(response, binding.audience(), expected, cache, authorityKeys, clock);
    }

    public synchronized AuthorizationLocalState state() {
        long now = now().getEpochSecond();
        if (now >= originalDeadline || now >= stableDeadline(context, snapshot)) return AuthorizationLocalState.EXPIRED;
        if (dirty) return AuthorizationLocalState.DIRTY;
        if (unverified) return AuthorizationLocalState.UNVERIFIED;
        if (now >= freshness.notAfterEpochSecond()) return AuthorizationLocalState.EXPIRED;
        return AuthorizationLocalState.VALID;
    }

    public synchronized void requirePermission(String operation) {
        List<String> validated = AuthzContract.permissions(List.of(AuthzContract.text(operation, AuthzContract.MAX_PERMISSION_LENGTH)));
        switch (state()) {
            case DIRTY -> throw new AuthzException(AuthzErrorCode.CONTEXT_CONFLICT);
            case UNVERIFIED -> throw new AuthzException(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE);
            case EXPIRED -> throw new AuthzException(AuthzErrorCode.EXPIRED);
            case VALID -> AuthzContract.check(allowedOperations.contains(validated.get(0)), AuthzErrorCode.PERMISSION_DENIED);
        }
    }

    /** Unsigned notifications can only tighten state. They never install snapshots or grant scope. */
    public synchronized void onNotification(String userId, long revision, long credentialEpoch) {
        AuthzContract.actor(userId);
        AuthzContract.require(revision > 0 && credentialEpoch >= 0);
        if (!actorId.equals(userId)) return;
        if (revision > highestRevision || credentialEpoch > highestEpoch) {
            highestRevision = Math.max(highestRevision, revision);
            highestEpoch = Math.max(highestEpoch, credentialEpoch);
            dirty = true;
        }
    }

    /** Used for unknown authority/invalid proofs; a brief outage alone need not call this method. */
    public synchronized void markUnverified() {
        unverified = true;
    }

    /**
     * Same-version authority renewal only. It may recover expired freshness before stable deadlines,
     * but never clears DIRTY/UNVERIFIED, changes context expiry or reauthenticates an expired run.
     */
    public synchronized void updateFreshness(AuthzFreshness newer) {
        Instant now = now();
        requireStableTime(now);
        AuthzSignature.verifyFreshness(newer, authorityKeys, now);
        AuthzContract.check(newer.userId().equals(actorId)
                && newer.permissionRevision() == context.permissionRevision()
                && newer.credentialEpoch() == context.credentialEpoch()
                && newer.permissionHash().equals(context.permissionHash()), AuthzErrorCode.CONTEXT_CONFLICT);
        AuthzContract.check(newer.checkedAtEpochSecond() > freshness.checkedAtEpochSecond(), AuthzErrorCode.CONTEXT_CONFLICT);
        // A later read with a shorter validity cannot create a sliding window either.
        freshness = newer;
    }

    /** Fully authenticated refresh: fixed execution identity/policy and original deadlines survive. */
    public synchronized void refresh(AuthzResponse response, ResponseCorrelation expected) {
        Instant now = now();
        requireStableTime(now);
        verify(response, audience, expected, now);
        AuthorizationContext next = response.context();
        AuthzContract.check(actorId.equals(next.actorId()) && Objects.equals(runId, next.runId())
                && Objects.equals(sessionId, next.sessionId()) && policyDigest.equals(next.policyDigest())
                && runHardDeadline == next.runHardDeadlineEpochSecond()
                && next.expiresAtEpochSecond() <= context.expiresAtEpochSecond()
                && next.expiresAtEpochSecond() <= originalDeadline
                && next.issuedAtEpochSecond() >= context.issuedAtEpochSecond()
                && next.permissionRevision() >= context.permissionRevision()
                && next.credentialEpoch() >= context.credentialEpoch()
                && next.permissionRevision() >= highestRevision && next.credentialEpoch() >= highestEpoch,
                AuthzErrorCode.CONTEXT_CONFLICT);
        // A still-valid older context must not restore scope removed by an accepted refresh.
        // Reject before replacing context so delegation accessors cannot expose regranted operations.
        AuthzContract.check(originalPolicyOperations.containsAll(next.allowedOperations())
                && allowedOperations.containsAll(next.allowedOperations()), AuthzErrorCode.PERMISSION_DENIED);
        AuthzContract.check(response.freshness().checkedAtEpochSecond() >= freshness.checkedAtEpochSecond()
                && (response.freshness().checkedAtEpochSecond() != freshness.checkedAtEpochSecond()
                || response.freshness().notAfterEpochSecond() <= freshness.notAfterEpochSecond()),
                AuthzErrorCode.CONTEXT_CONFLICT);
        if (next.permissionRevision() == context.permissionRevision()) {
            AuthzContract.check(next.permissionHash().equals(context.permissionHash()), AuthzErrorCode.CONTEXT_CONFLICT);
        }
        AuthorizationSnapshot shared = cache.intern(response.snapshot(), authorityKeys, now);
        List<String> permitted = next.allowedOperations().stream().filter(originalPolicyOperations::contains).toList();
        snapshot = shared;
        context = next;
        freshness = response.freshness();
        allowedOperations = permitted;
        originalDeadline = Math.min(originalDeadline, stableDeadline(next, shared));
        highestRevision = next.permissionRevision();
        highestEpoch = next.credentialEpoch();
        dirty = false;
        unverified = false;
    }

    /** Per-session single-flight refresh; never coalesces responses for different request bindings. */
    public void refreshSingleFlight(ResponseCorrelation expected, Supplier<AuthzResponse> loader) {
        AuthzContract.require(expected != null && loader != null);
        CompletableFuture<Void> flight;
        boolean owner = false;
        synchronized (this) {
            requireStableTime(now());
            if (refreshFlight == null) {
                refreshFlight = new CompletableFuture<>();
                refreshCorrelation = expected;
                owner = true;
            } else {
                AuthzContract.check(expected.equals(refreshCorrelation), AuthzErrorCode.CONTEXT_CONFLICT);
            }
            flight = refreshFlight;
        }
        if (owner) {
            try {
                refresh(loader.get(), expected);
                flight.complete(null);
            } catch (Throwable failure) {
                flight.completeExceptionally(failure instanceof AuthzException ? failure
                        : new AuthzException(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE));
                if (failure instanceof Error error) throw error;
            } finally {
                synchronized (this) {
                    if (refreshFlight == flight) {
                        refreshFlight = null;
                        refreshCorrelation = null;
                    }
                }
            }
        }
        try {
            flight.join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof AuthzException authz) throw authz;
            throw new AuthzException(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE);
        }
    }

    /** Dedicated server-side use only. These accessors must not be forwarded to logs/models. */
    public synchronized AuthorizationSnapshot snapshot() { return snapshot; }
    public synchronized AuthorizationContext context() { return context; }
    public synchronized AuthzFreshness freshness() { return freshness; }
    public synchronized List<String> allowedOperations() { return allowedOperations; }
    public synchronized AuthorizationKey key() { return AuthorizationKey.of(snapshot); }
    public synchronized long effectiveDeadlineEpochSecond() {
        return Math.min(originalDeadline, Math.min(stableDeadline(context, snapshot), freshness.notAfterEpochSecond()));
    }

    private void verify(AuthzResponse response, String expectedAudience, ResponseCorrelation expected, Instant now) {
        AuthzContract.require(expected != null);
        AuthzSignature.verifyResponse(response, expectedAudience, expected.requestId(), expected.requestHash(),
                expected.nonce(), authorityKeys, now);
    }

    private Instant now() {
        Instant value = clock.instant();
        if (value.getEpochSecond() < lastObservedSecond) {
            unverified = true;
            clockRollback = true;
            return Instant.ofEpochSecond(lastObservedSecond);
        }
        lastObservedSecond = value.getEpochSecond();
        return value;
    }

    private void requireStableTime(Instant now) {
        AuthzContract.check(!clockRollback, AuthzErrorCode.AUTHORIZATION_UNAVAILABLE);
        AuthzContract.check(now.getEpochSecond() < originalDeadline
                && now.getEpochSecond() < stableDeadline(context, snapshot), AuthzErrorCode.EXPIRED);
    }

    private static long stableDeadline(AuthorizationContext context, AuthorizationSnapshot snapshot) {
        long deadline = Math.min(context.expiresAtEpochSecond(), context.runHardDeadlineEpochSecond());
        return snapshot.grantDeadlineEpochSecond() == null ? deadline
                : Math.min(deadline, snapshot.grantDeadlineEpochSecond());
    }

    public record ResponseCorrelation(String requestId, String requestHash, String nonce) {
        public ResponseCorrelation {
            requestId = AuthzContract.text(requestId, 128);
            requestHash = AuthzContract.hash(requestHash);
            nonce = AuthzContract.nonce(nonce);
        }

        @Override public String toString() { return "ResponseCorrelation[REDACTED]"; }
    }

    @Override public String toString() { return "AuthorizationSession[REDACTED]"; }
}
