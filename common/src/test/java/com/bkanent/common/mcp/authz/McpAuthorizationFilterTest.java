package com.bkanent.common.mcp.authz;

import com.bkanent.common.authz.AuthzFreshness;
import com.bkanent.common.authz.AuthzSignature;
import com.bkanent.common.authz.AuthorizationContext;
import com.bkanent.common.authz.AuthorizationStatus;
import com.bkanent.common.security.ServiceIdentityCrypto;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class McpAuthorizationFilterTest {
    private static final long NOW = 1_800_000_000L;
    private static final String HASH = "b".repeat(64);
    private KeyPair authority;
    private KeyPair agent;
    private McpAuthorizationServerProperties properties;
    private McpAuthorizationReplayGuard replayGuard;
    private byte[] body;
    private String proof;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        authority = generator.generateKeyPair();
        agent = generator.generateKeyPair();
        properties = new McpAuthorizationServerProperties();
        properties.setEnabled(true);
        properties.setAuthorityPublicKeys(Map.of("auth-key", ServiceIdentityCrypto.encodePublicKey(authority.getPublic())));
        properties.setCallerPublicKeys(Map.of("agent-key", ServiceIdentityCrypto.encodePublicKey(agent.getPublic())));
        properties.setToolOperations(Map.of("search_transcripts", "interview.asset.read"));
        properties.validate("interview-service", "/mcp");
        replayGuard = new McpAuthorizationReplayGuard(32);
        body = "{\"jsonrpc\":\"2.0\",\"id\":\"mcp-2\",\"method\":\"tools/call\",\"params\":{\"name\":\"search_transcripts\",\"arguments\":{}}}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String contextNonce = nonce((byte) 2);
        AuthorizationContext context = AuthzSignature.sign(new AuthorizationContext("73", 4, 2, HASH,
                List.of("interview.asset.read"), "agent-service", "ingress", "run-20", "session-20", HASH,
                HASH, contextNonce, NOW - 1, NOW + 200, NOW + 200, "Ed25519", "auth-key", null),
                authority.getPrivate());
        AuthzFreshness freshness = AuthzSignature.sign(new AuthzFreshness("73", 4, 2,
                AuthorizationStatus.ACTIVE, HASH, NOW - 1, NOW + 29, nonce((byte) 4), "Ed25519", "auth-key", null),
                authority.getPrivate());
        proof = McpAuthorizationContract.sign(body, context, freshness, "agent-service", "interview-service",
                "search_transcripts", "interview.asset.read", "agent-key", agent.getPrivate(), Instant.ofEpochSecond(NOW));
    }

    @Test
    void verifiesBeforeDispatchAndRejectsReplay() throws Exception {
        McpAuthorizationFilter filter = filter();
        AtomicInteger calls = new AtomicInteger();
        FilterChain chain = (request, response) -> {
            calls.incrementAndGet();
            assertThat(McpAuthorizationScope.current()).isNotNull();
            assertThat(McpAuthorizationScope.current().actorId()).isEqualTo("73");
            assertThat(request.getAttribute(McpAuthorizationScope.REQUEST_ATTRIBUTE)).isNotNull();
            assertThat(request.getInputStream().readAllBytes()).containsExactly(body);
        };

        var first = post();
        var firstResponse = new MockHttpServletResponse();
        filter.doFilter(first, firstResponse, chain);
        assertThat(firstResponse.getStatus()).isEqualTo(200);
        assertThat(calls).hasValue(1);
        assertThat(McpAuthorizationScope.current()).isNull();

        var replay = post();
        var replayResponse = new MockHttpServletResponse();
        filter.doFilter(replay, replayResponse, chain);
        assertThat(replayResponse.getStatus()).isEqualTo(409);
        assertThat(calls).hasValue(1);
    }

    @Test
    void protectsCallsButAllowsOnlyMetadataWithoutUserDelegation() throws Exception {
        McpAuthorizationFilter filter = filter();
        AtomicInteger calls = new AtomicInteger();
        FilterChain chain = (request, response) -> calls.incrementAndGet();

        MockHttpServletRequest missingProof = post();
        missingProof.removeHeader(McpAuthorizationContract.HEADER);
        MockHttpServletResponse denied = new MockHttpServletResponse();
        filter.doFilter(missingProof, denied, chain);
        assertThat(denied.getStatus()).isEqualTo(401);

        MockHttpServletRequest metadata = new MockHttpServletRequest("POST", "/mcp");
        metadata.setRequestURI("/mcp");
        metadata.setContentType("application/json");
        metadata.setContent("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockHttpServletResponse allowed = new MockHttpServletResponse();
        filter.doFilter(metadata, allowed, chain);
        assertThat(allowed.getStatus()).isEqualTo(200);
        assertThat(calls).hasValue(1);
    }

    private McpAuthorizationFilter filter() {
        return new McpAuthorizationFilter("/mcp", "interview-service", properties, replayGuard,
                Clock.fixed(Instant.ofEpochSecond(NOW + 1), ZoneOffset.UTC));
    }

    private MockHttpServletRequest post() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.setRequestURI("/mcp");
        request.setContentType("application/json");
        request.setContent(body.clone());
        request.addHeader(McpAuthorizationContract.HEADER, proof);
        return request;
    }

    private static String nonce(byte fill) {
        byte[] value = new byte[32];
        java.util.Arrays.fill(value, fill);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
