package com.bkanent.common.a2a;

import com.bkanent.common.authz.AuthzFreshness;
import com.bkanent.common.authz.AuthzRpcOperation;
import com.bkanent.common.authz.AuthorizationContext;
import com.bkanent.common.authz.AuthorizationStatus;
import com.bkanent.common.authz.CallerProof;
import com.bkanent.common.authz.DelegatedAuthorizationRequest;
import com.bkanent.common.authz.DelegationBinding;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Test-only helper that opens the same already-verified delegation shape used by A2A handlers. */
public final class VerifiedKnowledgeContext {
    private VerifiedKnowledgeContext() { }

    public static A2aCredentialContext.Scope open(List<String> operations, Map<String, Long> identities) {
        return open(operations, identities, "1");
    }

    public static A2aCredentialContext.Scope open(List<String> operations, Map<String, Long> identities,
                                                   String actorId) {
        long now = Instant.now().getEpochSecond();
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        String hash = "0".repeat(64);
        var context = new AuthorizationContext(actorId, 1, 1, hash, operations,
                "platform-operations-service", "req-1", "run-1", "session-1", hash, hash, nonce,
                now - 1, now + 20, now + 21, identities, "Ed25519", "auth-current", null);
        var freshness = new AuthzFreshness(actorId, 1, 1, AuthorizationStatus.ACTIVE,
                hash, now - 1, now + 10, nonce, "Ed25519", "auth-current", null);
        var binding = new DelegationBinding("agent-service", "platform-operations-service", actorId,
                List.of("agent.workflow.invoke.platform-operations.knowledge"), "run-1", "task-1", "req-1", hash,
                hash, nonce, now - 1, now + 10);
        var proof = new CallerProof("agent-service", "platform-operations-service", AuthzRpcOperation.VALIDATE_DELEGATION,
                "req-1", hash, nonce, now - 1, now + 10, "Ed25519", "agent-current", null);
        var request = new DelegatedAuthorizationRequest(context, freshness, binding, proof);
        return A2aCredentialContext.open(A2aCredentialContext.Lease.versioned(request, "message/send", null));
    }
}
