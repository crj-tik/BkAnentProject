package com.bkanent.agent.authorization;

import com.bkanent.agent.mcp.AgentMcpClient;
import com.bkanent.agent.mcp.model.AgentMcpCallResult;
import com.bkanent.agent.mcp.model.AgentMcpToolDescriptor;
import com.bkanent.agent.orchestration.SupervisorCapabilityCatalog;
import com.bkanent.agent.registry.AgentRegistry;
import com.bkanent.agent.service.A2aExecutionService;
import com.bkanent.agent.service.AgentPermissionService;
import com.bkanent.common.authz.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.chat.model.ToolContext;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SupervisorMcpActorBindingTest {
    private static final long NOW = 1_800_000_000L;
    private static final String HASH = "b".repeat(64);

    @Test
    void modelUserIdCannotReplaceTheBoundActorAndBoundActorCanInvokeTheMappedTool() throws Exception {
        KeyPair authority = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Clock clock = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);
        AgentAuthorizationScope.Entry entry = entry(authority, clock);

        AgentRegistry registry = mock(AgentRegistry.class);
        AgentMcpClient mcp = mock(AgentMcpClient.class);
        AgentPermissionService permissions = mock(AgentPermissionService.class);
        A2aExecutionService execution = mock(A2aExecutionService.class);
        when(registry.listDescriptors()).thenReturn(List.of());
        when(permissions.versionedAuthorizationEnabled()).thenReturn(true);
        when(permissions.canInvokeMcpTool("73", "interview-mcp-server", "search_transcripts")).thenReturn(true);
        when(permissions.mcpToolPermissionCode("interview-mcp-server", "search_transcripts"))
                .thenReturn("interview.asset.read");
        when(permissions.mcpTargetAudience("interview-mcp-server")).thenReturn("interview-service");
        when(mcp.supportsUserDelegation("interview-mcp-server")).thenReturn(true);
        when(mcp.listTools()).thenReturn(List.of(new AgentMcpToolDescriptor("interview-mcp-server",
                "search_transcripts", "search", "{\"type\":\"object\"}", null)));
        when(mcp.callTool("interview-mcp-server", "search_transcripts", Map.of("keyword", "leased")))
                .thenReturn(new AgentMcpCallResult("interview-mcp-server", "search_transcripts", "ok", Map.of()));

        SupervisorCapabilityCatalog catalog = new SupervisorCapabilityCatalog(registry, mcp, permissions,
                execution, ToolCallbackProvider.from(), new ObjectMapper());
        try (var ignored = AgentAuthorizationScope.open(entry)) {
            var capability = catalog.snapshot("73", true).get("mcp:interview-mcp-server:search_transcripts");
            assertThat(capability).isNotNull();

            assertThatThrownBy(() -> capability.callback().call("{\"keyword\":\"leased\"}",
                    new ToolContext(Map.of("userId", "99"))))
                    .isInstanceOf(AuthzException.class)
                    .extracting(failure -> ((AuthzException) failure).code())
                    .isEqualTo(AuthzErrorCode.AUTHENTICATION_FAILED);
            verify(mcp, never()).callTool(anyString(), anyString(), anyMap());

            assertThat(capability.callback().call("{\"keyword\":\"leased\"}",
                    new ToolContext(Map.of("userId", "73")))).contains("ok");
            verify(mcp).callTool("interview-mcp-server", "search_transcripts", Map.of("keyword", "leased"));
        }
    }

    private AgentAuthorizationScope.Entry entry(KeyPair authority, Clock clock) {
        byte[] requestBody = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String requestNonce = nonce((byte) 6);
        RequestBinding binding = new RequestBinding("gateway", "agent-service", "POST", "/agent/chat", "/agent/chat",
                List.of(), "ingress-request", "run-19", "session-19", AuthzCanonicalHash.bodyHash(requestBody),
                HASH, requestNonce, NOW - 1, NOW + 59);
        AuthorizationSnapshot snapshot = AuthzSignature.sign(new AuthorizationSnapshot("73", 4, 1, false,
                List.of("agent.chat.use", "interview.asset.read"), null,
                AuthzContract.ALGORITHM, "auth-key", null), authority.getPrivate());
        String permissionHash = AuthzCanonicalHash.snapshotHash(snapshot);
        String requestHash = AuthzCanonicalHash.resolveRequestHash("verified-access-token", binding);
        AuthorizationContext context = AuthzSignature.sign(new AuthorizationContext("73", 4, 2, permissionHash,
                snapshot.permissions(), "agent-service", binding.requestId(), binding.runId(), binding.sessionId(),
                binding.policyDigest(), requestHash, binding.nonce(), NOW - 1, NOW + 200, NOW + 200,
                AuthzContract.ALGORITHM, "auth-key", null), authority.getPrivate());
        AuthzFreshness freshness = AuthzSignature.sign(new AuthzFreshness("73", 4, 2,
                AuthorizationStatus.ACTIVE, permissionHash, NOW - 1, NOW + 29, nonce((byte) 7),
                AuthzContract.ALGORITHM, "auth-key", null), authority.getPrivate());
        AuthzResponse response = new AuthzResponse(snapshot, context, freshness);
        AuthorizationSession.ResponseCorrelation correlation = new AuthorizationSession.ResponseCorrelation(
                binding.requestId(), requestHash, binding.nonce());
        AuthorizationSession session = AuthorizationSession.fromEntry(response, "agent-service", correlation,
                new AuthorizationCache(8, 1_048_576, Duration.ofMinutes(1), 8),
                Map.of("auth-key", authority.getPublic()), clock);
        return new AgentAuthorizationScope.Entry(response, binding, session);
    }

    private static String nonce(byte fill) {
        byte[] value = new byte[32];
        java.util.Arrays.fill(value, fill);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
