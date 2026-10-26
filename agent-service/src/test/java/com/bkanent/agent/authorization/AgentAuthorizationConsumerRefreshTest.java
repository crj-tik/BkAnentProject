package com.bkanent.agent.authorization;

import com.bkanent.agent.config.AgentAuthorizationProperties;
import com.bkanent.agent.service.AgentPermissionService;
import com.bkanent.agent.service.PermissionAuditService;
import com.bkanent.common.authz.*;
import com.bkanent.common.rpc.AuthPermissionRpcService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentAuthorizationConsumerRefreshTest {
    private static final long NOW = 1_700_000_000L;
    private final MutableClock clock = new MutableClock(NOW);
    private KeyPair authorityKey;
    private KeyPair callerKey;
    private AgentAuthorizationProperties properties;
    private AuthAuthorizationRpcService auth;
    private AuthorizationVersionMonitor monitor;
    private AgentAuthorizationConsumer consumer;
    private AtomicReference<AuthzFreshness> head;
    private Path keyFile;
    private AgentAuthorizationScope.Entry entry;

    @BeforeEach
    void setUp() throws Exception {
        var generator = KeyPairGenerator.getInstance("Ed25519");
        authorityKey = generator.generateKeyPair();
        callerKey = generator.generateKeyPair();
        properties = new AgentAuthorizationProperties();
        properties.setEnabled(true);
        properties.setKeyId("agent-key");
        properties.setPublicKey(Base64.getEncoder().encodeToString(callerKey.getPublic().getEncoded()));
        keyFile = Files.createTempFile("agent-authz-refresh-", ".pem");
        Files.writeString(keyFile, "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(callerKey.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n");
        properties.setPrivateKeyPath(keyFile.toString());
        properties.setAuthorityPublicKeys(Map.of("auth-key",
                Base64.getEncoder().encodeToString(authorityKey.getPublic().getEncoded())));
        properties.setCapabilityPermissions(Map.of("local:milvusKnowledgeSearch", "agent.rag.search"));
        auth = mock(AuthAuthorizationRpcService.class);
        head = new AtomicReference<>();
        monitor = new AuthorizationVersionMonitor(users -> {
            AuthzFreshness value = head.get();
            return value == null ? Map.of() : Map.of("42", value);
        }, properties.authorityKeys(), clock);
        consumer = new AgentAuthorizationConsumer(properties,
                new AuthorizationCache(20, 1_000_000, java.time.Duration.ofMinutes(5), 4),
                monitor, clock, auth);
        entry = admitEntry();
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (keyFile != null) Files.deleteIfExists(keyFile);
    }

    @Test
    void unchangedHeadReusesTheRunSessionWithoutAuthRefresh() throws Exception {
        head.set(entry.session().freshness());
        try (var scope = AgentAuthorizationScope.open(entry)) {
            assertThat(consumer.checkPlanning(entry, "real-token")).isEqualTo(AuthorizationLocalState.VALID);
            entry.session().requirePermission("agent.workflow.read");
        }
        verifyNoInteractions(auth);
    }

    @Test
    void versionedPermissionChecksUseTheSignedActorAndLocalSessionOnly() throws Exception {
        AuthPermissionRpcService legacyAuth = mock(AuthPermissionRpcService.class);
        var permissions = new AgentPermissionService(legacyAuth, mock(PermissionAuditService.class));
        permissions.setAuthorizationProperties(properties);
        try (var scope = AgentAuthorizationScope.open(entry)) {
            assertThat(permissions.canUseLocalTool("42", "milvusKnowledgeSearch")).isTrue();
            assertThat(permissions.canUseLocalTool("42", "unknownTool")).isFalse();
            assertThat(permissions.canInvokeMcpTool("42", "business-mcp-server", "search")).isFalse();
            assertThatThrownBy(() -> permissions.canUseLocalTool("43", "milvusKnowledgeSearch"))
                    .isInstanceOf(AuthzException.class)
                    .extracting(failure -> ((AuthzException) failure).code())
                    .isEqualTo(AuthzErrorCode.AUTHENTICATION_FAILED);
        }
        verifyNoInteractions(legacyAuth);
    }

    @Test
    void capturedWorkerContextRestoresSignedActorAndClearsBothThreadLocals() throws Exception {
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        var credentials = new com.bkanent.agent.security.AgentRagCredentialLeaseStore(
                new com.bkanent.agent.config.AgentRagConsumerProperties(),
                beans.getBeanProvider(AuthPermissionRpcService.class), clock);
        AgentPermissionService permissions = new AgentPermissionService(
                mock(AuthPermissionRpcService.class), mock(PermissionAuditService.class));
        permissions.setAuthorizationProperties(properties);
        java.util.function.Supplier<AutoCloseable> captured;
        try (var scope = AgentAuthorizationScope.open(entry);
             var verifiedCredentials = credentials.openVerifiedAuthorization("real-token", entry)) {
            credentials.capture("run-1", "42");
            captured = credentials.snapshot();
        }

        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(() -> {
                try (var restored = captured.get()) {
                    assertThat(AgentAuthorizationScope.current()).isSameAs(entry);
                    assertThat(permissions.canUseLocalTool("42", "milvusKnowledgeSearch")).isTrue();
                    assertThat(credentials.credentials("42").accessToken()).isEqualTo("real-token");
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
                assertThat(AgentAuthorizationScope.current()).isNull();
                assertThatThrownBy(() -> credentials.credentials("42"))
                        .hasMessage("AUTHENTICATION_REQUIRED");
            }).get();
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void changedHeadRefreshesOnlyTheSameRunAndShrinksItsOriginalPolicy() throws Exception {
        clock.advance(1);
        head.set(freshness(2, AuthzCanonicalHash.snapshotHash(snapshot(2, List.of("agent.chat.use"))),
                clock.instant().getEpochSecond(), "head-nonce-2"));
        when(auth.refreshAuthorization(anyString(), any(), any(), any())).thenAnswer(call -> {
            String token = call.getArgument(0);
            AuthorizationContext original = call.getArgument(1);
            RequestBinding binding = call.getArgument(2);
            CallerProof proof = call.getArgument(3);
            String expectedHash = AuthzCanonicalHash.refreshRequestHash(token, original, binding);
            assertThat(token).isEqualTo("real-token");
            assertThat(binding.method()).isEqualTo("POST");
            assertThat(binding.originalPath()).isEqualTo("/authz/refresh");
            assertThat(binding.runId()).isEqualTo("run-1");
            assertThat(binding.sessionId()).isEqualTo("session-1");
            assertThat(binding.policyDigest()).isEqualTo(original.policyDigest());
            AuthzSignature.verifyRefreshBinding(original, binding, properties.authorityKeys(), clock.instant());
            AuthzSignature.verifyCallerProof(proof, "agent-service", "auth-service",
                    AuthzRpcOperation.REFRESH_AUTHORIZATION, binding.requestId(), expectedHash, binding.nonce(),
                    Set.of(AuthzRpcOperation.REFRESH_AUTHORIZATION), Map.of("agent-key", callerKey.getPublic()),
                    clock.instant());
            var refreshedSnapshot = snapshot(2, List.of("agent.chat.use"));
            String permissionHash = AuthzCanonicalHash.snapshotHash(refreshedSnapshot);
            var context = AuthzSignature.sign(new AuthorizationContext("42", 2, 0, permissionHash,
                    List.of("agent.chat.use"), "agent-service", binding.requestId(), binding.runId(),
                    binding.sessionId(), binding.policyDigest(), expectedHash, binding.nonce(),
                    clock.instant().getEpochSecond(), original.expiresAtEpochSecond(),
                    original.runHardDeadlineEpochSecond(), AuthzContract.ALGORITHM, "auth-key", null),
                    authorityKey.getPrivate());
            var newFreshness = freshness(2, permissionHash, clock.instant().getEpochSecond(), binding.nonce());
            return new AuthzResponse(refreshedSnapshot, context, newFreshness);
        });

        try (var scope = AgentAuthorizationScope.open(entry)) {
            assertThat(consumer.checkPlanning(entry, "real-token")).isEqualTo(AuthorizationLocalState.VALID);
            assertThat(entry.session().context().runId()).isEqualTo("run-1");
            assertThat(entry.session().context().sessionId()).isEqualTo("session-1");
            assertThat(entry.session().context().permissionRevision()).isEqualTo(2);
            entry.session().requirePermission("agent.chat.use");
            assertThatThrownBy(() -> entry.session().requirePermission("agent.workflow.read"))
                    .isInstanceOf(AuthzException.class)
                    .extracting(failure -> ((AuthzException) failure).code())
                    .isEqualTo(AuthzErrorCode.PERMISSION_DENIED);
        }
        verify(auth, times(1)).refreshAuthorization(anyString(), any(), any(), any());
    }

    private AgentAuthorizationScope.Entry admitEntry() {
        byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String nonce = nonce("ingress-nonce-1");
        String policyDigest = AuthzCanonicalHash.bodyHash(new byte[0]);
        var binding = new RequestBinding("gateway", "agent-service", "POST", "/agent/probe", "/agent/probe",
                List.of(), "ingress-request", "run-1", "session-1", AuthzCanonicalHash.bodyHash(body),
                policyDigest, nonce, NOW, NOW + 60);
        var snapshot = snapshot(1, List.of("agent.chat.use", "agent.workflow.read", "agent.rag.search"));
        String permissionHash = AuthzCanonicalHash.snapshotHash(snapshot);
        String requestHash = AuthzCanonicalHash.resolveRequestHash("real-token", binding);
        var context = AuthzSignature.sign(new AuthorizationContext("42", 1, 0, permissionHash,
                snapshot.permissions(), "agent-service", binding.requestId(), binding.runId(), binding.sessionId(),
                binding.policyDigest(), requestHash, binding.nonce(), NOW, NOW + 300, NOW + 300,
                AuthzContract.ALGORITHM, "auth-key", null), authorityKey.getPrivate());
        var response = new AuthzResponse(snapshot, context,
                freshness(1, permissionHash, NOW, binding.nonce()));
        return consumer.verify(response, binding, "real-token", "POST", "/agent/probe", null, body);
    }

    private AuthorizationSnapshot snapshot(long revision, List<String> permissions) {
        return AuthzSignature.sign(new AuthorizationSnapshot("42", revision, 1, false, permissions, null,
                AuthzContract.ALGORITHM, "auth-key", null), authorityKey.getPrivate());
    }

    private AuthzFreshness freshness(long revision, String permissionHash, long checkedAt, String nonce) {
        return AuthzSignature.sign(new AuthzFreshness("42", revision, 0, AuthorizationStatus.ACTIVE,
                permissionHash, checkedAt, checkedAt + 30, nonce(nonce), AuthzContract.ALGORITHM, "auth-key", null),
                authorityKey.getPrivate());
    }

    private static String nonce(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong seconds;
        private MutableClock(long seconds) { this.seconds = new AtomicLong(seconds); }
        void advance(long amount) { seconds.addAndGet(amount); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochSecond(seconds.get()); }
    }
}
