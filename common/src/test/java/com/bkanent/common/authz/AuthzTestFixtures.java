package com.bkanent.common.authz;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;

final class AuthzTestFixtures {
    static final long NOW = 1_800_000_000L;
    static final String KID = "auth-key-1";
    static final String NONCE = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    static final String HASH = AuthzCanonicalHash.bodyHash(new byte[0]);
    static final List<String> PERMISSIONS = List.of("agent.chat.use", "interview.read", "memory.read");

    private AuthzTestFixtures() { }

    static KeyPair keyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    static AuthorizationSnapshot snapshot() {
        return new AuthorizationSnapshot("123", 7, 3, false, PERMISSIONS, null,
                AuthzContract.ALGORITHM, KID, null);
    }

    static AuthorizationContext context(AuthorizationSnapshot snapshot) {
        return new AuthorizationContext(snapshot.actorId(), snapshot.permissionRevision(), 0,
                AuthzCanonicalHash.snapshotHash(snapshot), PERMISSIONS, "agent-service", "entry-request",
                "run-1", "session-1", HASH, HASH, NONCE, NOW, NOW + 300, NOW + 300,
                AuthzContract.ALGORITHM, KID, null);
    }

    static AuthzFreshness freshness(AuthorizationSnapshot snapshot) {
        return new AuthzFreshness(snapshot.actorId(), snapshot.permissionRevision(), 0, AuthorizationStatus.ACTIVE,
                AuthzCanonicalHash.snapshotHash(snapshot), NOW, NOW + 30, NONCE, AuthzContract.ALGORITHM, KID, null);
    }

    static DelegationBinding binding() {
        return new DelegationBinding("agent-service", "memory-service", "123", List.of("memory.read"),
                "run-1", "task-1", "child-request", HASH, HASH, NONCE, NOW, NOW + 60);
    }

    static RequestBinding request(List<RequestBinding.QueryParameter> query) {
        return new RequestBinding("gateway", "agent-service", "POST", "/api/chat", "/chat", query,
                "entry-request", "run-1", "session-1", HASH, HASH, NONCE, NOW, NOW + 60);
    }

    static CallerProof caller(DelegationBinding binding, AuthorizationContext parent) {
        return new CallerProof("agent-service", "auth-service", AuthzRpcOperation.VALIDATE_DELEGATION,
                binding.requestId(), AuthzCanonicalHash.delegationRequestHash(parent, binding), binding.nonce(),
                NOW, NOW + 60, AuthzContract.ALGORITHM, "caller-key-1", null);
    }
}
