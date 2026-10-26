package com.bkanent.common.a2a;

import com.bkanent.common.authz.*;
import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.fasterxml.jackson.databind.JsonNode;
import io.a2a.util.Utils;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Clock;
import java.util.Map;
import java.util.Set;

/** Verifies Agent-signed A2A request bindings locally, before TaskStore/queue access. */
public final class A2aAuthorizationVerifier {
    public static final String CALLER_SERVICE = "agent-service";
    private static final Set<AuthzRpcOperation> CALLER_ACL = Set.of(AuthzRpcOperation.VALIDATE_DELEGATION);

    private final String audience;
    private final A2aAuthorizationProperties properties;
    private final Clock clock;
    private final Map<String, PublicKey> authorityKeys;
    private final Map<String, PublicKey> callerKeys;
    private final A2aTaskAuthorizationStore stateStore;

    public A2aAuthorizationVerifier(String audience, A2aAuthorizationProperties properties, Clock clock,
                                    A2aTaskAuthorizationStore stateStore) {
        if (audience == null || !audience.matches("[a-z][a-z0-9-]{0,95}") || properties == null || clock == null) {
            throw new IllegalArgumentException("A2A authorization verifier configuration is required");
        }
        this.audience = audience;
        this.properties = properties;
        this.clock = clock;
        this.authorityKeys = properties.isEnabled() ? properties.authorityKeys() : Map.of();
        this.callerKeys = properties.isEnabled() ? properties.trustedCallerKeys() : Map.of();
        this.stateStore = stateStore;
    }

    public boolean enabled() { return properties.isEnabled(); }

    /** A nonce is consumed after cryptographic and wire-binding checks, before any A2A side effect. */
    public VerifiedRequest verify(String encoded, String body) {
        try {
            if (!enabled()) throw new AuthzException(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE);
            DelegatedAuthorizationRequest request = AuthzWireCodec.decodeDelegatedAuthorizationRequest(encoded);
            if (body == null) throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
            byte[] rawBody = body.getBytes(StandardCharsets.UTF_8);
            if (rawBody.length > AuthzContract.MAX_BODY_BYTES)
                throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
            RequestFacts facts = requestFacts(body);
            AuthorizationContext context = request.context();
            AuthzFreshness freshness = request.freshness();
            DelegationBinding binding = request.binding();
            CallerProof proof = request.callerProof();
            var now = clock.instant();

            AuthzSignature.verifyContext(context, CALLER_SERVICE, authorityKeys, now);
            AuthzSignature.verifyFreshness(freshness, authorityKeys, now);
            if (!freshness.userId().equals(context.actorId())
                    || freshness.permissionRevision() != context.permissionRevision()
                    || freshness.credentialEpoch() != context.credentialEpoch()
                    || !freshness.permissionHash().equals(context.permissionHash())) {
                throw new AuthzException(AuthzErrorCode.CONTEXT_CONFLICT);
            }
            if (context.runId() == null || context.runId().isBlank()
                    || context.sessionId() == null || context.sessionId().isBlank()) {
                throw new AuthzException(AuthzErrorCode.CONTEXT_CONFLICT);
            }
            if (!CALLER_SERVICE.equals(binding.callerService()) || !audience.equals(binding.audience())
                    || !binding.requestId().equals(facts.requestId())
                    || !binding.taskId().equals(facts.taskId())
                    || !binding.bodyHash().equals(AuthzCanonicalHash.bodyHash(rawBody))
                    || binding.operations().size() != 1
                    || binding.expiresAtEpochSecond() > freshness.notAfterEpochSecond()) {
                throw new AuthzException(AuthzErrorCode.CONTEXT_CONFLICT);
            }
            AuthzSignature.verifyDelegationBinding(context, binding, binding, authorityKeys, now);
            String requestHash = AuthzCanonicalHash.delegationRequestHash(context, binding);
            AuthzSignature.verifyCallerProof(proof, CALLER_SERVICE, audience,
                    AuthzRpcOperation.VALIDATE_DELEGATION, binding.requestId(), requestHash, binding.nonce(),
                    CALLER_ACL, callerKeys, now);
            if (stateStore == null) throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
            if (!stateStore.consumeNonce(binding.nonce(), binding.expiresAtEpochSecond(), now.getEpochSecond()))
                throw new AuthzException(AuthzErrorCode.REPLAY_DETECTED);
            return new VerifiedRequest(request, facts, binding.operations().get(0));
        } catch (RagException failure) {
            throw failure;
        } catch (org.springframework.dao.DataAccessException failure) {
            throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        } catch (AuthzException failure) {
            throw map(failure.code());
        } catch (RuntimeException failure) {
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        }
    }

    private RequestFacts requestFacts(String body) {
        try {
            if (body == null) throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
            JsonNode node = Utils.OBJECT_MAPPER.readTree(body);
            JsonNode id = node.get("id");
            if (id == null || !(id.isTextual() || id.isIntegralNumber())) {
                throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
            }
            String method = node.path("method").asText();
            boolean sending = method.equals("message/send") || method.equals("message/stream");
            if (!sending && !Set.of("tasks/get", "tasks/cancel", "tasks/resubscribe").contains(method)) {
                throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
            }
            JsonNode params = node.path("params");
            if (sending && !params.path("configuration").path("pushNotification").isMissingNode()
                    && !params.path("configuration").path("pushNotification").isNull()) {
                throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
            }
            String taskId = sending ? params.path("message").path("taskId").asText()
                    : params.path("id").asText();
            if (id.asText().isBlank() || taskId.isBlank()) throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
            String contextId = sending ? params.path("message").path("contextId").asText(null) : null;
            return new RequestFacts(id.asText(), method, taskId, contextId, sending);
        } catch (AuthzException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
        }
    }

    public void bindOrVerifyTaskOwner(VerifiedRequest request) {
        try {
            if (stateStore == null) throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
            AuthorizationContext context = request.envelope().context();
            DelegationBinding binding = request.envelope().binding();
            RequestFacts facts = request.facts();
            boolean ownerMatches = stateStore.bindOrVerifyTaskOwner(facts.taskId(), context.actorId(),
                    binding.callerService(), facts.contextId(), context.runId(), context.sessionId(), facts.sending());
            if (!ownerMatches) throw new RagException(RagErrorCode.FORBIDDEN);
        } catch (RagException failure) {
            throw failure;
        } catch (org.springframework.dao.DataAccessException failure) {
            throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public void reserveTask(VerifiedRequest request) {
        try {
            if (stateStore == null) throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
            AuthorizationContext context = request.envelope().context();
            DelegationBinding binding = request.envelope().binding();
            long expiresAt = Math.min(context.expiresAtEpochSecond(),
                    Math.min(context.runHardDeadlineEpochSecond(), request.envelope().freshness().notAfterEpochSecond()));
            if (!stateStore.reserveTask(request.facts().taskId(), context.actorId(), binding.nonce(),
                    expiresAt, clock.instant().getEpochSecond())) {
                throw new RagException(RagErrorCode.FORBIDDEN);
            }
        } catch (RagException failure) {
            throw failure;
        } catch (org.springframework.dao.DataAccessException failure) {
            throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public void releaseTaskReservation(VerifiedRequest request) {
        if (stateStore == null) return;
        try {
            stateStore.releaseTaskReservation(request.facts().taskId(), request.envelope().binding().nonce());
        } catch (org.springframework.dao.DataAccessException failure) {
            throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private static RagException map(AuthzErrorCode code) {
        return new RagException(switch (code) {
            case PERMISSION_DENIED -> RagErrorCode.FORBIDDEN;
            case EXPIRED -> RagErrorCode.AUTHENTICATION_EXPIRED;
            case AUTHORIZATION_UNAVAILABLE -> RagErrorCode.DEPENDENCY_UNAVAILABLE;
            case INVALID_ARGUMENT -> RagErrorCode.INVALID_ARGUMENT;
            default -> RagErrorCode.AUTHENTICATION_FAILED;
        });
    }

    public record RequestFacts(String requestId, String method, String taskId, String contextId, boolean sending) { }
    public record VerifiedRequest(DelegatedAuthorizationRequest envelope, RequestFacts facts, String operation) { }
    @Override public String toString() { return "A2aAuthorizationVerifier[REDACTED]"; }
}
