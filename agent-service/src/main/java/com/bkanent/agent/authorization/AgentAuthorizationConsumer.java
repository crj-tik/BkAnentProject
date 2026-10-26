package com.bkanent.agent.authorization;

import com.bkanent.agent.config.AgentAuthorizationProperties;
import com.bkanent.common.authz.*;
import org.springframework.scheduling.annotation.Scheduled;

import java.security.PrivateKey;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** Verified ingress/local sessions and bounded refresh at business planning boundaries. */
public final class AgentAuthorizationConsumer {
    private final AgentAuthorizationProperties properties;
    private final AuthorizationCache cache;
    private final AuthorizationVersionMonitor monitor;
    private final Clock clock;
    private final AuthAuthorizationRpcService auth;
    private final SecureRandom random = new SecureRandom();
    private volatile PrivateKey serviceKey;

    public AgentAuthorizationConsumer(AgentAuthorizationProperties properties, AuthorizationCache cache,
                                      AuthorizationVersionMonitor monitor, Clock clock) {
        this(properties, cache, monitor, clock, null);
    }

    public AgentAuthorizationConsumer(AgentAuthorizationProperties properties, AuthorizationCache cache,
                                      AuthorizationVersionMonitor monitor, Clock clock,
                                      AuthAuthorizationRpcService auth) {
        this.properties = properties;
        this.cache = cache;
        this.monitor = monitor;
        this.clock = clock;
        this.auth = auth;
    }

    public AgentAuthorizationScope.Entry verify(AuthzResponse response, RequestBinding binding, String token,
                                                String method, String path, String query, byte[] body) {
        if (body.length > properties.getMaxBodyBytes()) throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
        AuthzWireCodec.verifyIngress(response, binding, token, properties.getAudience(), method, path,
                AuthzWireCodec.parseQuery(query), body, properties.authorityKeys(), clock.instant());
        var correlation = new AuthorizationSession.ResponseCorrelation(binding.requestId(),
                AuthzCanonicalHash.resolveRequestHash(token, binding), binding.nonce());
        var session = AuthorizationSession.fromEntry(response, properties.getAudience(), correlation, cache,
                properties.authorityKeys(), clock);
        monitor.registry().register(session);
        var entry = new AgentAuthorizationScope.Entry(response, binding, session);
        entry.registrationRelease(monitor.registry()::unregister);
        return entry;
    }

    public void bind(AgentAuthorizationScope.Entry entry, ExecutionBinding binding, AuthzResponse response) {
        AuthzSignature.verifyExecutionBindingResponse(entry.response(), binding, response,
                properties.authorityKeys(), clock.instant());
        var correlation = new AuthorizationSession.ResponseCorrelation(binding.requestId(),
                AuthzCanonicalHash.executionBindingRequestHash(entry.response(), binding), binding.nonce());
        var bound = AuthorizationSession.fromEntry(response, properties.getAudience(), correlation, cache,
                properties.authorityKeys(), clock);
        var original = entry.session();
        // Register first so a concurrent known version/epoch notice cannot be forgotten by replacement.
        monitor.registry().register(bound);
        try {
            entry.requireActor(binding.actorId());
            if (bound.state() != AuthorizationLocalState.VALID) throw new AuthzException(AuthzErrorCode.CONTEXT_CONFLICT);
            entry.installBoundSession(bound, binding, AuthzCanonicalHash.executionBindingTupleHash(entry.response(), binding));
            monitor.registry().unregister(original);
        } catch (RuntimeException failure) {
            monitor.registry().unregister(bound);
            throw failure;
        }
    }

    public void releaseIngress(AgentAuthorizationScope.Entry entry) {
        if (!entry.hasCapturedLease()) entry.releaseRegistration();
    }

    /**
     * Called once when an existing run replans. Head reads are single-flight per actor in the
     * monitor; a changed head is refreshed only for this already-bound run/session. No token,
     * signed proof, or Auth response is cached outside this process-local execution scope.
     */
    public AuthorizationLocalState checkPlanning(AgentAuthorizationScope.Entry entry, String token) {
        if (!properties.isEnabled()) throw new AuthzException(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE);
        if (entry == null || token == null || token.isBlank()
                || entry != AgentAuthorizationScope.current())
            throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
        synchronized (entry) {
            entry.requireRunAssociation();
            var session = entry.session();
            AuthorizationLocalState state = monitor.checkPlanning(session);
            if (state == AuthorizationLocalState.VALID) return state;
            if (state != AuthorizationLocalState.DIRTY) throw stateFailure(state);
            if (auth == null) throw new AuthzException(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE);

            var original = session.context();
            long now = clock.instant().getEpochSecond();
            long expiresAt = Math.min(now + AuthzDeadlines.REQUEST_SECONDS,
                    Math.min(original.expiresAtEpochSecond(), original.runHardDeadlineEpochSecond()));
            if (expiresAt <= now) throw new AuthzException(AuthzErrorCode.EXPIRED);
            byte[] nonceBytes = new byte[32];
            random.nextBytes(nonceBytes);
            String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
            String requestId = UUID.randomUUID().toString();
            // This is an internal RPC proof with an empty HTTP body; all security facts are
            // nevertheless covered by refreshRequestHash below.
            String emptyBodyHash = AuthzCanonicalHash.bodyHash(new byte[0]);
            var binding = new RequestBinding(properties.getCallerService(), properties.getAudience(), "POST",
                    "/authz/refresh", "/authz/refresh", List.of(), requestId, original.runId(),
                    original.sessionId(), emptyBodyHash, original.policyDigest(), nonce, now, expiresAt);
            String requestHash = AuthzCanonicalHash.refreshRequestHash(token, original, binding);
            var proof = AuthzSignature.sign(new CallerProof(properties.getCallerService(), "auth-service",
                    AuthzRpcOperation.REFRESH_AUTHORIZATION, requestId, requestHash, nonce, now, expiresAt,
                    AuthzContract.ALGORITHM, properties.getKeyId(), null), signingKey());
            var correlation = new AuthorizationSession.ResponseCorrelation(requestId, requestHash, nonce);
            session.refreshSingleFlight(correlation, () -> auth.refreshAuthorization(token, original, binding, proof));
            state = session.state();
            if (state != AuthorizationLocalState.VALID) throw stateFailure(state);
            return state;
        }
    }

    private PrivateKey signingKey() {
        PrivateKey value = serviceKey;
        if (value != null) return value;
        synchronized (this) {
            if (serviceKey == null) serviceKey = properties.signingKey();
            return serviceKey;
        }
    }

    private static AuthzException stateFailure(AuthorizationLocalState state) {
        return switch (state) {
            case DIRTY -> new AuthzException(AuthzErrorCode.CONTEXT_CONFLICT);
            case UNVERIFIED -> new AuthzException(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE);
            case EXPIRED -> new AuthzException(AuthzErrorCode.EXPIRED);
            case VALID -> throw new IllegalArgumentException("valid authorization state has no failure");
        };
    }

    public AuthorizationVersionMonitor monitor() { return monitor; }
    @Scheduled(fixedDelay = 10000)
    public void reconcile() { monitor.reconcileBatch(clock); }
    @Override public String toString() { return "AgentAuthorizationConsumer[REDACTED]"; }
}
