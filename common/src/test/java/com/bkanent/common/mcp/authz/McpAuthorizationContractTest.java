package com.bkanent.common.mcp.authz;

import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.authz.AuthzFreshness;
import com.bkanent.common.authz.AuthzSignature;
import com.bkanent.common.authz.AuthorizationContext;
import com.bkanent.common.authz.AuthorizationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpAuthorizationContractTest {
    private static final long NOW = 1_800_000_000L;
    private static final String HASH = "a".repeat(64);
    private KeyPair authority;
    private KeyPair agent;
    private AuthorizationContext context;
    private AuthzFreshness freshness;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        authority = generator.generateKeyPair();
        agent = generator.generateKeyPair();
        String nonce = nonce((byte) 3);
        context = AuthzSignature.sign(new AuthorizationContext("73", 4, 2, HASH,
                List.of("interview.asset.read"), "agent-service", "ingress-request", "run-19", "session-19",
                HASH, HASH, nonce, NOW - 1, NOW + 200, NOW + 200, "Ed25519", "auth-key", null),
                authority.getPrivate());
        freshness = AuthzSignature.sign(new AuthzFreshness("73", 4, 2, AuthorizationStatus.ACTIVE, HASH,
                NOW - 1, NOW + 29, nonce((byte) 4), "Ed25519", "auth-key", null), authority.getPrivate());
    }

    @Test
    void signsAndLocallyVerifiesTheExactJsonRpcCallWithoutAuthRoundTrip() {
        byte[] body = body("search_transcripts", "keyword", "公开内容");
        String proof = signed(body);

        var verified = McpAuthorizationContract.verify(proof, body, "agent-service", "interview-service",
                "search_transcripts", "interview.asset.read", Map.of("auth-key", authority.getPublic()),
                Map.of("agent-key", agent.getPublic()), Instant.ofEpochSecond(NOW + 1));

        assertThat(verified.actorId()).isEqualTo("73");
        assertThat(verified.context().runId()).isEqualTo("run-19");
        assertThat(verified.requestId()).isEqualTo("mcp-1");
        assertThat(verified.toolName()).isEqualTo("search_transcripts");
    }

    @Test
    void rejectsChangedBodyToolOperationAudienceOrCallerKey() {
        byte[] body = body("search_transcripts", "keyword", "公开内容");
        String proof = signed(body);
        assertThatThrownBy(() -> McpAuthorizationContract.verify(proof,
                body("search_transcripts", "keyword", "改过的内容"), "agent-service", "interview-service",
                "search_transcripts", "interview.asset.read", Map.of("auth-key", authority.getPublic()),
                Map.of("agent-key", agent.getPublic()), Instant.ofEpochSecond(NOW + 1)))
                .isInstanceOf(AuthzException.class);
        assertThatThrownBy(() -> McpAuthorizationContract.verify(proof, body, "agent-service", "platform-operations-service",
                "search_transcripts", "interview.asset.read", Map.of("auth-key", authority.getPublic()),
                Map.of("agent-key", agent.getPublic()), Instant.ofEpochSecond(NOW + 1)))
                .isInstanceOf(AuthzException.class);
        assertThatThrownBy(() -> McpAuthorizationContract.verify(proof, body, "agent-service", "interview-service",
                "get_case_report", "interview.asset.read", Map.of("auth-key", authority.getPublic()),
                Map.of("agent-key", agent.getPublic()), Instant.ofEpochSecond(NOW + 1)))
                .isInstanceOf(AuthzException.class);
        assertThatThrownBy(() -> McpAuthorizationContract.verify(proof, body, "agent-service", "interview-service",
                "search_transcripts", "interview.report.generate", Map.of("auth-key", authority.getPublic()),
                Map.of("agent-key", agent.getPublic()), Instant.ofEpochSecond(NOW + 1)))
                .isInstanceOf(AuthzException.class);
        assertThatThrownBy(() -> McpAuthorizationContract.verify(proof, body, "agent-service", "interview-service",
                "search_transcripts", "interview.asset.read", Map.of("auth-key", authority.getPublic()),
                Map.of("other-agent-key", agent.getPublic()), Instant.ofEpochSecond(NOW + 1)))
                .isInstanceOf(AuthzException.class);
    }

    @Test
    void rejectsDuplicateJsonKeysTrailingJsonAndExpiredFreshness() {
        assertThatThrownBy(() -> McpAuthorizationContract.parse(
                "{\"jsonrpc\":\"2.0\",\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"search_transcripts\"}}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(AuthzException.class);
        assertThatThrownBy(() -> McpAuthorizationContract.parse((new String(body("search_transcripts", "keyword", "x")) + "{}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8))).isInstanceOf(AuthzException.class);

        String proof = signed(body("search_transcripts", "keyword", "公开内容"));
        assertThatThrownBy(() -> McpAuthorizationContract.verify(proof, body("search_transcripts", "keyword", "公开内容"),
                "agent-service", "interview-service", "search_transcripts", "interview.asset.read",
                Map.of("auth-key", authority.getPublic()), Map.of("agent-key", agent.getPublic()),
                Instant.ofEpochSecond(NOW + 30))).isInstanceOf(AuthzException.class);
    }

    @Test
    void replayGuardAtomicallyConsumesOneNonceAndRemainsBounded() {
        McpAuthorizationReplayGuard guard = new McpAuthorizationReplayGuard(1);
        String nonce = nonce((byte) 8);
        guard.consume("agent-service", "interview-service", nonce, NOW + 10, NOW);
        assertThatThrownBy(() -> guard.consume("agent-service", "interview-service", nonce, NOW + 10, NOW))
                .isInstanceOf(AuthzException.class)
                .extracting(failure -> ((AuthzException) failure).code())
                .isEqualTo(AuthzErrorCode.REPLAY_DETECTED);
        assertThatThrownBy(() -> guard.consume("agent-service", "interview-service", nonce((byte) 9), NOW + 10, NOW))
                .isInstanceOf(AuthzException.class)
                .extracting(failure -> ((AuthzException) failure).code())
                .isEqualTo(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE);
    }

    private String signed(byte[] body) {
        return McpAuthorizationContract.sign(body, context, freshness, "agent-service", "interview-service",
                "search_transcripts", "interview.asset.read", "agent-key", agent.getPrivate(), Instant.ofEpochSecond(NOW));
    }

    private static byte[] body(String tool, String key, String value) {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":\"mcp-1\",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + tool + "\",\"arguments\":{\"" + key + "\":\"" + value + "\"}}}";
        return json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String nonce(byte fill) {
        byte[] value = new byte[32];
        java.util.Arrays.fill(value, fill);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
