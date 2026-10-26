package com.bkanent.agent.authorization;

import com.bkanent.agent.config.AgentRagConsumerProperties;
import com.bkanent.agent.milvus.memory.AgentMemoryMilvusService;
import com.bkanent.agent.security.AgentRagCredentialLeaseStore;
import com.bkanent.agent.security.AgentRagRequestAuthentication;
import com.bkanent.common.authz.*;
import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentRagContextPropagationTest {
    private static final byte[] RAG_SECRET = "agent-rag-signing-secret-at-least-32-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Test
    void supervisorRagRecallForwardsBoundContextAndRequestProofWithoutUserBearer() throws Exception {
        long now = Instant.now().getEpochSecond();
        Clock clock = Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC);
        KeyPair authority = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var entry = entry(authority, clock, now);
        var properties = new AgentRagConsumerProperties();
        properties.setVersionedAuthorizationRequired(true);
        properties.setKnowledgeEnabled(true);
        properties.setSupervisorOwnerConfirmed(true);
        properties.setSupervisorOwnerDomain("agent-service");
        properties.setSupervisorPolicyVersion("policy-v1");
        properties.setKeyId("agent-rag-key");
        properties.setSigningSecret(new String(RAG_SECRET, java.nio.charset.StandardCharsets.UTF_8));

        var beans = new DefaultListableBeanFactory();
        var retrieval = mock(RagRetrievalRpcService.class);
        var memory = mock(MemoryRagRpcService.class);
        var admin = mock(RagSpaceAdminRpcService.class);
        var auth = mock(AuthPermissionRpcService.class);
        beans.registerSingleton("retrieval", retrieval);
        beans.registerSingleton("memory", memory);
        beans.registerSingleton("admin", admin);
        beans.registerSingleton("auth", auth);
        var credentials = new AgentRagCredentialLeaseStore(properties,
                beans.getBeanProvider(AuthPermissionRpcService.class));
        var authentication = new AgentRagRequestAuthentication(properties);
        var wrapper = new AgentMemoryMilvusService(beans.getBeanProvider(RagRetrievalRpcService.class),
                beans.getBeanProvider(MemoryRagRpcService.class), beans.getBeanProvider(RagSpaceAdminRpcService.class),
                credentials, authentication);

        when(retrieval.search(any(), any(), isNull(), any())).thenAnswer(call -> {
            RagSearchRequest request = call.getArgument(0);
            RagAuthEnvelope proof = call.getArgument(1);
            RagAuthorizationScope scope = call.getArgument(3);
            assertEquals("supervisor.knowledge", request.spaceId());
            assertEquals("7", proof.actorId());
            assertNull(proof.credentialDigest());
            RagRequestSigner.verify(proof, RagCanonicalHash.requestHash(request), "rag-service", RagOperation.SEARCH,
                    request.requestId(), null,
                    new RagSigningKey("agent-rag-key", "agent-service", null, Set.of(RagOperation.SEARCH), RAG_SECRET),
                    Instant.now());
            assertTrue(scope.versionedAuthorization());
            assertEquals("agent.rag.search", scope.operationCode());
            assertEquals("7", scope.actorId());
            assertEquals("7", scope.ownerId());
            assertFalse(scope.businessWideRead());
            assertTrue(scope.activeOnly());
            assertEquals(entry.session().context().runId(), scope.authorizationContext().context().runId());
            assertEquals(entry.session().context().sessionId(), scope.authorizationContext().context().sessionId());
            assertEquals(entry.session().context(), scope.authorizationContext().context());
            assertEquals(entry.session().freshness(), scope.authorizationContext().freshness());
            return new RagSearchResponse(1, request.requestId(), request.spaceId(), "policy-v1",
                    RagSearchMode.HYBRID, RagSearchMode.HYBRID, false, List.of(), List.of(), List.of());
        });

        try (var authScope = AgentAuthorizationScope.open(entry);
             var bearerLease = credentials.openVerifiedAuthorization("user-bearer-must-not-forward", entry)) {
            assertThat(wrapper.searchKnowledge("7", null, "query", 5)).isEmpty();
        }
        verify(retrieval).search(any(), any(), isNull(), any());
        verifyNoInteractions(auth, memory, admin);
    }

    private static AgentAuthorizationScope.Entry entry(KeyPair authority, Clock clock, long now) {
        byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String nonce = nonce((byte) 8);
        var binding = new RequestBinding("gateway", "agent-service", "POST", "/agent/chat", "/agent/chat",
                List.of(), "ingress-request", "run-rag-7", "session-rag-7", AuthzCanonicalHash.bodyHash(body),
                "a".repeat(64), nonce, now - 1, now + 59);
        var snapshot = AuthzSignature.sign(new AuthorizationSnapshot("7", 17, 1, false,
                List.of("agent.rag.search"), null, AuthzContract.ALGORITHM, "auth-key", null), authority.getPrivate());
        String requestHash = AuthzCanonicalHash.resolveRequestHash("verified-access-token", binding);
        var context = AuthzSignature.sign(new AuthorizationContext("7", 17, 4,
                AuthzCanonicalHash.snapshotHash(snapshot), snapshot.permissions(), "agent-service",
                binding.requestId(), binding.runId(), binding.sessionId(), binding.policyDigest(), requestHash,
                binding.nonce(), now - 1, now + 60, now + 299, AuthzContract.ALGORITHM, "auth-key", null),
                authority.getPrivate());
        var freshness = AuthzSignature.sign(new AuthzFreshness("7", 17, 4, AuthorizationStatus.ACTIVE,
                AuthzCanonicalHash.snapshotHash(snapshot), now - 1, now + 29, nonce((byte) 9),
                AuthzContract.ALGORITHM, "auth-key", null), authority.getPrivate());
        var response = new AuthzResponse(snapshot, context, freshness);
        var correlation = new AuthorizationSession.ResponseCorrelation(binding.requestId(), requestHash, binding.nonce());
        var session = AuthorizationSession.fromEntry(response, "agent-service", correlation,
                new AuthorizationCache(8, 1_048_576, Duration.ofMinutes(1), 8),
                Map.of("auth-key", authority.getPublic()), clock);
        return new AgentAuthorizationScope.Entry(response, binding, session);
    }

    private static String nonce(byte fill) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, fill);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
