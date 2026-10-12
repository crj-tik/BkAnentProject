package com.bkanent.agent.security;

import com.bkanent.agent.config.AgentRagConsumerProperties;
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
        long now = Instant.now().getEpochSecond();
        return RagRequestSigner.sign(new RagAuthEnvelope(1, properties.getKeyId(), audience, "agent-service", operation,
                requestId, RagCanonicalHash.requestHash(request), RagActorMode.USER, userId, now, now + 60,
                RagCanonicalHash.credentialDigest(credentials), null), secret());
    }
    public RagAuthorizationScope supervisorScope(RagSearchRequest request, String userId) {
        // Ownership is an explicit migration gate, never inferred from a uid/gateway marker.
        if (!"supervisor.knowledge".equals(request.spaceId())
                || !properties.isKnowledgeEnabled() || !properties.isSupervisorOwnerConfirmed()
                || !"agent-service".equals(properties.getSupervisorOwnerDomain())
                || properties.getSupervisorPolicyVersion() == null
                || !properties.getSupervisorReadUserIds().contains(userId)) throw new RagException(RagErrorCode.FORBIDDEN);
        long now = Instant.now().getEpochSecond();
        return RagScopeSigner.sign(new RagAuthorizationScope(1, properties.getKeyId(), "agent-service", "agent-service",
                "supervisor.knowledge", userId, RagOperation.SEARCH, RagCanonicalHash.requestHash(request),
                properties.getSupervisorPolicyVersion(), now, now + 60, userId, java.util.List.of(), true, null), secret());
    }
    private byte[] secret() {
        if (properties.getKeyId() == null || properties.getSigningSecret() == null)
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        byte[] value = properties.getSigningSecret().getBytes(StandardCharsets.UTF_8);
        if (value.length < 32) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        return value;
    }
}
