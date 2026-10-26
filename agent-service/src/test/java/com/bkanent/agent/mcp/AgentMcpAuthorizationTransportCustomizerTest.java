package com.bkanent.agent.mcp;

import com.bkanent.agent.authorization.AgentAuthorizationConsumer;
import com.bkanent.agent.authorization.AgentAuthorizationScope;
import com.bkanent.agent.config.AgentAuthorizationProperties;
import com.bkanent.common.authz.*;
import com.bkanent.common.mcp.authz.McpAuthorizationContract;
import com.bkanent.common.security.ServiceIdentityCrypto;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentMcpAuthorizationTransportCustomizerTest {
    private static final String ACTOR = "73";
    private static final String ACCESS_TOKEN = "verified-test-token";
    private static final String SERVER = "interview-mcp-server";
    private static final String TOOL = "search_transcripts";
    private static final String AUDIENCE = "interview-service";
    private static final String OPERATION = "interview.asset.read";
    private static final long NOW = 1_800_000_000L;
    private static final URI ENDPOINT = URI.create("https://interview.example/mcp");

    @TempDir
    Path temporaryDirectory;

    @Test
    void repeatedToolCallsUseLocalBodyProofsWithoutAdditionalAuthOrRedisReads() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        KeyPair authority = generator.generateKeyPair();
        KeyPair caller = generator.generateKeyPair();
        Clock clock = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);

        AgentAuthorizationProperties properties = new AgentAuthorizationProperties();
        properties.setEnabled(true);
        properties.setKeyId("agent-key");
        properties.setPublicKey(ServiceIdentityCrypto.encodePublicKey(caller.getPublic()));
        properties.setAuthorityPublicKeys(Map.of("auth-key", ServiceIdentityCrypto.encodePublicKey(authority.getPublic())));
        Path privateKey = temporaryDirectory.resolve("agent-private.pem");
        Files.writeString(privateKey, "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(caller.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n");
        properties.setPrivateKeyPath(privateKey.toString());

        AtomicInteger headReads = new AtomicInteger();
        AtomicReference<AuthzFreshness> currentHead = new AtomicReference<>();
        AuthorizationVersionMonitor monitor = new AuthorizationVersionMonitor(users -> {
            headReads.incrementAndGet();
            return Map.of(ACTOR, currentHead.get());
        }, properties.authorityKeys(), clock);
        AuthAuthorizationRpcService auth = mock(AuthAuthorizationRpcService.class);
        AgentAuthorizationConsumer consumer = new AgentAuthorizationConsumer(properties,
                new com.bkanent.common.authz.AuthorizationCache(16, 1_000_000,
                        java.time.Duration.ofMinutes(1), 8), monitor, clock, auth);

        byte[] ingressBody = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String ingressNonce = nonce((byte) 1);
        String policyDigest = AuthzCanonicalHash.bodyHash(new byte[0]);
        RequestBinding ingress = new RequestBinding("gateway", "agent-service", "POST", "/agent/chat", "/agent/chat",
                List.of(), "ingress-request", "run-19", "session-19", AuthzCanonicalHash.bodyHash(ingressBody),
                policyDigest, ingressNonce, NOW - 1, NOW + 59);
        AuthorizationSnapshot snapshot = AuthzSignature.sign(new AuthorizationSnapshot(ACTOR, 4, 1, false,
                List.of("agent.chat.use", OPERATION), null, AuthzContract.ALGORITHM, "auth-key", null),
                authority.getPrivate());
        String permissionHash = AuthzCanonicalHash.snapshotHash(snapshot);
        String ingressHash = AuthzCanonicalHash.resolveRequestHash(ACCESS_TOKEN, ingress);
        AuthorizationContext context = AuthzSignature.sign(new AuthorizationContext(ACTOR, 4, 2, permissionHash,
                snapshot.permissions(), "agent-service", ingress.requestId(), ingress.runId(), ingress.sessionId(),
                ingress.policyDigest(), ingressHash, ingress.nonce(), NOW - 1, NOW + 120, NOW + 120,
                AuthzContract.ALGORITHM, "auth-key", null), authority.getPrivate());
        AuthzFreshness freshness = AuthzSignature.sign(new AuthzFreshness(ACTOR, 4, 2, AuthorizationStatus.ACTIVE,
                permissionHash, NOW - 1, NOW + 29, nonce((byte) 2), AuthzContract.ALGORITHM, "auth-key", null),
                authority.getPrivate());
        currentHead.set(freshness);
        AgentAuthorizationScope.Entry entry = consumer.verify(new AuthzResponse(snapshot, context, freshness), ingress,
                ACCESS_TOKEN, "POST", "/agent/chat", null, ingressBody);

        try (var authorization = AgentAuthorizationScope.open(entry)) {
            assertThat(consumer.checkPlanning(entry, ACCESS_TOKEN)).isEqualTo(AuthorizationLocalState.VALID);
        }
        assertThat(headReads).hasValue(1);

        AgentMcpAuthorizationSupport support = mock(AgentMcpAuthorizationSupport.class);
        when(support.target(SERVER)).thenReturn(new AgentMcpAuthorizationSupport.Target(SERVER, AUDIENCE, ENDPOINT));
        AgentMcpAuthorizationTransportCustomizer customizer =
                new AgentMcpAuthorizationTransportCustomizer(properties, support, clock);
        McpClient.SyncSpec spec = mock(McpClient.SyncSpec.class);
        AtomicReference<Supplier<McpTransportContext>> contextProvider = new AtomicReference<>();
        doAnswer(call -> {
            contextProvider.set(call.getArgument(0));
            return null;
        }).when(spec).transportContextProvider(any());

        AgentMcpAuthorizationInvocation invocation = new AgentMcpAuthorizationInvocation(entry, SERVER, TOOL,
                true, false, AUDIENCE, OPERATION);
        Set<String> nonces = new java.util.HashSet<>();
        try (var authorization = AgentAuthorizationScope.open(entry);
             var mcpScope = AgentMcpAuthorizationScope.open(invocation)) {
            customizer.customize(SERVER, spec);
            for (int index = 0; index < 5; index++) {
                String body = "{\"jsonrpc\":\"2.0\",\"id\":" + index
                        + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + TOOL
                        + "\",\"arguments\":{\"keyword\":\"k" + index + "\"}}}";
                HttpRequest.Builder request = HttpRequest.newBuilder(ENDPOINT);
                customizer.customize(request, "POST", ENDPOINT, body, contextProvider.get().get());
                String proof = request.build().headers().firstValue(McpAuthorizationContract.HEADER).orElseThrow();
                var verified = McpAuthorizationContract.verify(proof,
                        body.getBytes(java.nio.charset.StandardCharsets.UTF_8), "agent-service", AUDIENCE, TOOL,
                        OPERATION, properties.authorityKeys(), Map.of("agent-key", caller.getPublic()), clock.instant());
                assertThat(verified.actorId()).isEqualTo(ACTOR);
                assertThat(verified.binding().bodyHash()).isEqualTo(AuthzCanonicalHash.bodyHash(
                        body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                nonces.add(verified.binding().nonce());
            }
        }

        assertThat(nonces).hasSize(5);
        assertThat(headReads).hasValue(1);
        verifyNoInteractions(auth);
    }

    private static String nonce(byte value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
