package com.bkanent.agent.authorization;

import com.bkanent.agent.config.AgentAuthorizationProperties;
import com.bkanent.common.authz.*;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;

/** Verified ingress/local sessions only; checkPlanning is deliberately not yet Graph-wired. */
public final class AgentAuthorizationConsumer {
    private final AgentAuthorizationProperties properties;
    private final AuthorizationCache cache;
    private final AuthorizationVersionMonitor monitor;
    private final Clock clock;

    public AgentAuthorizationConsumer(AgentAuthorizationProperties properties, AuthorizationCache cache,
                                      AuthorizationVersionMonitor monitor, Clock clock) {
        this.properties = properties;
        this.cache = cache;
        this.monitor = monitor;
        this.clock = clock;
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

    public AuthorizationVersionMonitor monitor() { return monitor; }
    @Scheduled(fixedDelay = 10000)
    public void reconcile() { monitor.reconcileBatch(clock); }
    @Override public String toString() { return "AgentAuthorizationConsumer[REDACTED]"; }
}
