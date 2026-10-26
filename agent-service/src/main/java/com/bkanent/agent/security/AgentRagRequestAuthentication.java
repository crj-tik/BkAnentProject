package com.bkanent.agent.security;

import com.bkanent.agent.config.AgentRagConsumerProperties;
import com.bkanent.agent.authorization.AgentAuthorizationScope;
import com.bkanent.common.rag.*;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Component
public class AgentRagRequestAuthentication {
    private final AgentRagConsumerProperties properties;
    public AgentRagRequestAuthentication(AgentRagConsumerProperties properties) { this.properties = properties; }
    public RagAuthEnvelope user(Object request, String requestId, String audience, RagOperation operation,
                                String userId, RagCredentialContext credentials) {
        if (properties.isVersionedAuthorizationRequired()) {
            var entry = AgentAuthorizationScope.current();
            if (entry == null) throw new com.bkanent.common.authz.AuthzException(
                    com.bkanent.common.authz.AuthzErrorCode.AUTHENTICATION_REQUIRED);
            entry.requireActor(userId);
            String permission = requiredPermission(request, audience, operation);
            entry.session().requirePermission(permission);
        }
        long now = Instant.now().getEpochSecond();
        boolean contextBoundRagSearch = properties.isVersionedAuthorizationRequired()
                && "rag-service".equals(audience) && operation == RagOperation.SEARCH;
        return RagRequestSigner.sign(new RagAuthEnvelope(1, properties.getKeyId(), audience, "agent-service", operation,
                requestId, RagCanonicalHash.requestHash(request), RagActorMode.USER, userId, now, now + 60,
                contextBoundRagSearch ? null : RagCanonicalHash.credentialDigest(credentials), null), secret());
    }
    public RagAuthorizationScope supervisorScope(RagSearchRequest request, String userId) {
        // Ownership is an explicit migration gate, never inferred from a uid/gateway marker.
        if (!"supervisor.knowledge".equals(request.spaceId())
                || !properties.isKnowledgeEnabled() || !properties.isSupervisorOwnerConfirmed()
                || !"agent-service".equals(properties.getSupervisorOwnerDomain())
                || properties.getSupervisorPolicyVersion() == null
                || !properties.isVersionedAuthorizationRequired() && !properties.getSupervisorReadUserIds().contains(userId))
            throw new RagException(RagErrorCode.FORBIDDEN);
        long now = Instant.now().getEpochSecond();
        if (properties.isVersionedAuthorizationRequired()) {
            var entry = AgentAuthorizationScope.current();
            if (entry == null) throw new com.bkanent.common.authz.AuthzException(
                    com.bkanent.common.authz.AuthzErrorCode.AUTHENTICATION_REQUIRED);
            entry.requireActor(userId);
            entry.session().requirePermission("agent.rag.search");
            var context = entry.session().context();
            var snapshot = entry.session().snapshot();
            var authorization = new com.bkanent.common.authz.AuthzResponse(snapshot, context, entry.session().freshness());
            long expiry = Math.min(now + 60, Math.min(context.expiresAtEpochSecond(), context.runHardDeadlineEpochSecond()));
            return RagScopeSigner.sign(new RagAuthorizationScope(1, properties.getKeyId(), "agent-service", "agent-service",
                    "supervisor.knowledge", userId, RagOperation.SEARCH, RagCanonicalHash.requestHash(request),
                    properties.getSupervisorPolicyVersion(), now, expiry, userId, java.util.List.of(), true, false,
                    AuthRagPrincipal.VERSIONED_AUTHORIZATION_VERSION, context.permissionRevision(), context.credentialEpoch(),
                    snapshot.businessAdmin(), "agent.rag.search", null, authorization, null), secret());
        }
        return RagScopeSigner.sign(new RagAuthorizationScope(1, properties.getKeyId(), "agent-service", "agent-service",
                "supervisor.knowledge", userId, RagOperation.SEARCH, RagCanonicalHash.requestHash(request),
                properties.getSupervisorPolicyVersion(), now, now + 60, userId, java.util.List.of(), true, null), secret());
    }

    private static String requiredPermission(Object request, String audience, RagOperation operation) {
        if ("rag-service".equals(audience)) {
            return operation == RagOperation.SEARCH ? "agent.rag.search" : "rag.space.manage";
        }
        if ("listing-master-service".equals(audience)) {
            return operation == RagOperation.SEARCH || operation == RagOperation.READ
                    ? "listing.read" : "agent.vector-store.manage";
        }
        if ("memory-service".equals(audience)) {
            String space = request instanceof RagSearchRequest search ? search.spaceId()
                    : request instanceof MemoryRagSearchRequest search ? search.search().spaceId() : null;
            if ("memory.constraint".equals(space)) return "memory.constraint.read";
            return "memory.search";
        }
        throw new RagException(RagErrorCode.FORBIDDEN);
    }
    /** RAG search consumes the bound Auth context; the user's bearer must not cross that RPC boundary. */
    public RagCredentialContext downstreamCredentials(RagCredentialContext credentials) {
        return properties.isVersionedAuthorizationRequired() ? null : credentials;
    }
    private byte[] secret() {
        if (properties.getKeyId() == null || properties.getSigningSecret() == null)
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        byte[] value = properties.getSigningSecret().getBytes(StandardCharsets.UTF_8);
        if (value.length < 32) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        return value;
    }
}
