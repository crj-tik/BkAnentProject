package com.bkanent.memory.runtime;

import com.bkanent.common.authz.*;
import com.bkanent.common.memory.*;
import com.bkanent.common.rpc.AuthenticatedPrincipal;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryRpcClientAuthorizationTest {
    @Test
    void sendsTheBoundAuthContextWithARequestSpecificMemoryProof() throws Exception {
        var memoryIdentity = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var authAuthority = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        long now = Instant.now().getEpochSecond();
        String authKeyId = "auth-memory-client-test";
        String permissionHash = "a".repeat(64);
        String authNonce = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        var context = AuthzSignature.sign(new AuthorizationContext("42", 5, 2, permissionHash,
                List.of("agent.chat.use"), "agent-service", "entry", "run-1", "session-1",
                "b".repeat(64), "c".repeat(64), authNonce, now, now + 120, now + 240,
                AuthzContract.ALGORITHM, authKeyId, null), authAuthority.getPrivate());
        var freshness = AuthzSignature.sign(new AuthzFreshness("42", 5, 2, AuthorizationStatus.ACTIVE,
                permissionHash, now, now + 30, authNonce, AuthzContract.ALGORITHM, authKeyId, null),
                authAuthority.getPrivate());
        AtomicReference<MemoryWorkingSetRequest> receivedRequest = new AtomicReference<>();
        AtomicReference<MemoryRequestProof> receivedProof = new AtomicReference<>();
        var rpc = (MemoryV2RpcService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{MemoryV2RpcService.class}, (proxy, method, args) -> {
                    if (method.getName().equals("readWorkingSet")) {
                        receivedRequest.set((MemoryWorkingSetRequest) args[0]);
                        receivedProof.set((MemoryRequestProof) args[1]);
                        return new MemoryWorkingSetResponse(receivedRequest.get().contextKey(),
                                new MemoryConversationVersion(0, 0, "policy-v1"), List.of(), List.of(), List.of());
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        var client = new MemoryRpcClient(rpc, "agent-service", "agent-memory-k1", memoryIdentity.getPrivate(),
                () -> new MemoryRpcClient.Actor(new AuthenticatedPrincipal(42L), now + 120, context, freshness));

        client.read(new MemoryContextKey("42", "supervisor", "supervisor-agent", "conversation-1"));

        var proof = receivedProof.get();
        assertThat(proof.signatureVersion()).isEqualTo(MemoryRequestProof.ED25519_AUTHZ_V3);
        assertThat(proof.authorizationContext()).isEqualTo(context);
        assertThat(proof.authorizationFreshness()).isEqualTo(freshness);
        assertThat(proof.expiresAtEpochSecond()).isLessThanOrEqualTo(freshness.notAfterEpochSecond());
        MemoryRequestSigner.verifyEd25519(receivedRequest.get(), receivedRequest.get().contextKey(), proof,
                "agent-service", MemoryV2Contract.MEMORY_AUDIENCE, MemoryV2Contract.OPERATION_WORKING_SET,
                "agent-memory-k1", Instant.ofEpochSecond(now), memoryIdentity.getPublic());
    }
}
