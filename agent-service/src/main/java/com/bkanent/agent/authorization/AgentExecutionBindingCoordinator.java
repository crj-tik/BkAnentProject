package com.bkanent.agent.authorization;

import com.bkanent.agent.config.AgentAuthorizationProperties;
import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.agent.orchestration.OrchestrationStore;
import com.bkanent.common.authz.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.security.PrivateKey;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** One independent binding at an admitted execution boundary, never a tool/model callback. */
@Component
public final class AgentExecutionBindingCoordinator {
    private final AgentAuthorizationProperties properties;
    private final ObjectProvider<AuthAuthorizationRpcService> auth;
    private final ObjectProvider<AgentAuthorizationConsumer> consumers;
    private final OrchestrationStore store;
    private final Clock clock;
    private final PrivateKey key;
    private final SecureRandom random = new SecureRandom();

    @org.springframework.beans.factory.annotation.Autowired
    public AgentExecutionBindingCoordinator(AgentAuthorizationProperties properties,
            @org.springframework.beans.factory.annotation.Qualifier("agentExecutionAuthorizationRpc")
            ObjectProvider<AuthAuthorizationRpcService> auth, ObjectProvider<AgentAuthorizationConsumer> consumers,
            OrchestrationStore store) {
        this(properties, auth, consumers, store, Clock.systemUTC(),
                properties.isEnabled() ? properties.signingKey() : null);
    }

    public AgentExecutionBindingCoordinator(AgentAuthorizationProperties properties,
            ObjectProvider<AuthAuthorizationRpcService> auth, ObjectProvider<AgentAuthorizationConsumer> consumers,
            OrchestrationStore store,
            Clock clock, PrivateKey key) {
        this.properties = properties; this.auth = auth; this.consumers = consumers;
        this.store = store; this.clock = clock; this.key = key;
    }

    public void bind(SupervisorTaskRequest request) {
        if (!properties.isEnabled()) return;
        if (request == null || blank(request.userId()) || blank(request.requestId()) || blank(request.sessionId()))
            throw new AuthzException(AuthzErrorCode.CONTEXT_CONFLICT);
        String runId = blank(request.continueRunId()) ? request.requestId() : request.continueRunId();
        String existingSession = store.existingSession(runId, request.userId());
        if (existingSession != null) {
            store.assertOwner(runId, request.userId());
            if (!existingSession.equals(request.sessionId())) throw new AuthzException(AuthzErrorCode.CONTEXT_CONFLICT);
            if (blank(request.continueRunId())) store.assertOriginalRequest(runId, request);
            else {
                String id = "input-" + store.hash(store.json(Map.of("requestId", request.requestId())));
                var previous = store.find(runId, id);
                var input = new java.util.LinkedHashMap<String, Object>();
                input.put("message", request.userMessage());
                if (request.matterIntent() != null) input.put("matterIntent", request.matterIntent());
                if (previous != null && !previous.argumentsHash().equals(store.hash(store.json(input))))
                    throw new AuthzException(AuthzErrorCode.CONTEXT_CONFLICT);
            }
        } else if (!blank(request.continueRunId())) throw new AuthzException(AuthzErrorCode.CONTEXT_CONFLICT);
        String executionHash = store.executionHash(request);
        bindTuple(request.userId(), runId, request.sessionId(), executionHash);
        // Persist the admitted tuple before async handoff, so omitted-session retries see the same run.
        if (blank(request.continueRunId())) store.register(runId, request);
    }

    /** Caller must have checked the actual fixed approval/checkpoint before using this boundary. */
    public void bindApproval(String actor, String runId, String sessionId, Object validatedDecision) {
        if (!properties.isEnabled()) return;
        store.assertOwner(runId, actor);
        if (!Objects.equals(store.originalRequest(runId).sessionId(), sessionId))
            throw new AuthzException(AuthzErrorCode.CONTEXT_CONFLICT);
        bindTuple(actor, runId, sessionId, store.hash(store.json(Map.of(
                "originalRequest", store.originalRequest(runId), "approvalDecision", validatedDecision))));
    }

    private void bindTuple(String actor, String runId, String sessionId, String executionHash) {
        var entry = AgentAuthorizationScope.current();
        if (entry == null) throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
        synchronized (entry) {
            entry.requireActor(actor);
            var original = entry.response().context();
            long now = clock.instant().getEpochSecond();
            long deadline = Math.min(now + AuthzDeadlines.REQUEST_SECONDS,
                    Math.min(original.expiresAtEpochSecond(), original.runHardDeadlineEpochSecond()));
            byte[] nonceBytes = new byte[32]; random.nextBytes(nonceBytes);
            String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
            var binding = new ExecutionBinding(properties.getCallerService(), actor, runId, sessionId,
                    original.allowedOperations(), original.requestHash(), executionHash, original.policyDigest(),
                    UUID.randomUUID().toString(), nonce, now, deadline, AuthzContract.ALGORITHM);
            String tuple = AuthzCanonicalHash.executionBindingTupleHash(entry.response(), binding);
            if (entry.executionTupleHash() != null) {
                if (!entry.executionTupleHash().equals(tuple)) throw new AuthzException(AuthzErrorCode.CONTEXT_CONFLICT);
                entry.requireRunAssociation();
                return;
            }
            if (original.runId() != null || original.sessionId() != null)
                throw new AuthzException(AuthzErrorCode.CONTEXT_CONFLICT);
            AuthzSignature.verifyResponse(entry.response(), properties.getAudience(), original.requestId(),
                    original.requestHash(), original.nonce(), properties.authorityKeys(), clock.instant());
            String hash = AuthzCanonicalHash.executionBindingRequestHash(entry.response(), binding);
            var proof = AuthzSignature.sign(new CallerProof(properties.getCallerService(), "auth-service",
                    AuthzRpcOperation.BIND_EXECUTION, binding.requestId(), hash, nonce, now, deadline,
                    AuthzContract.ALGORITHM, properties.getKeyId(), null), key);
            var provider = auth.getIfAvailable();
            var consumer = consumers.getIfAvailable();
            if (provider == null || consumer == null) throw new AuthzException(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE);
            try {
                var response = provider.bindExecution(entry.response(), binding, proof);
                consumer.bind(entry, binding, response);
            } catch (AuthzException failure) { throw failure; }
            catch (RuntimeException failure) { throw new AuthzException(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE); }
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    @Override public String toString() { return "AgentExecutionBindingCoordinator[REDACTED]"; }
}
