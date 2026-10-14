package com.bkanent.agent.security;

import com.bkanent.agent.config.AgentRagConsumerProperties;
import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.common.rag.RagException;
import com.bkanent.common.rpc.AuthPermissionRpcService;
import com.bkanent.common.rpc.AuthenticatedPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentRagCredentialLeaseStoreTest {
    private final AgentRagConsumerProperties properties = new AgentRagConsumerProperties();
    private final AuthPermissionRpcService auth = mock(AuthPermissionRpcService.class);
    private final MutableClock clock = new MutableClock();
    private AgentRagCredentialLeaseStore store() {
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("auth", auth);
        return new AgentRagCredentialLeaseStore(properties, beans.getBeanProvider(AuthPermissionRpcService.class), clock);
    }
    @Test void missingHeaderCannotMintCredentialFromUidOrMetadata() throws Exception {
        var store = store();
        try (var ignored = store.openAuthorization(null)) {
            store.capture("run", "1");
            try (var lease = store.openLease("run", "1")) {
                assertThatThrownBy(() -> store.credentials("1")).isInstanceOf(AgentRagAuthenticationException.class).hasMessage("AUTHENTICATION_REQUIRED");
            }
        }
        verifyNoInteractions(auth);
    }
    @Test void leaseExpiresWithoutRefreshAndFreshHeaderRevalidatesOriginalOwner() throws Exception {
        var store = store();
        when(auth.resolvePrincipal("transport-only")).thenReturn(new AuthenticatedPrincipal(1L));
        try (var ignored = store.openAuthorization("Bearer transport-only")) { store.capture("run", "1"); }
        clock.millis += 299_000;
        try (var lease = store.openLease("run", "1")) { store.capture("run", "1"); assertThat(store.credentials("1").accessToken()).isEqualTo("transport-only"); }
        clock.millis += 1001;
        try (var lease = store.openLease("run", "1")) { assertThatThrownBy(() -> store.credentials("1")).hasMessage("AUTHENTICATION_EXPIRED"); }
        try (var ignored = store.openAuthorization("Bearer transport-only")) { store.capture("run", "1"); }
        try (var lease = store.openLease("run", "1")) { assertThat(store.credentials("1").toString()).doesNotContain("transport-only"); }
        when(auth.resolvePrincipal("transport-only")).thenReturn(new AuthenticatedPrincipal(2L));
        try (var lease = store.openLease("run", "1")) { assertThatThrownBy(() -> store.credentials("1")).hasMessage("AUTHENTICATION_FAILED"); }
    }
    @Test void revokedTokenDependencyAndLeaseCapacityAreDistinctAndSafe() throws Exception {
        properties.setMaxCredentialLeases(1);
        var store = store();
        try (var ignored = store.openAuthorization("Bearer private-value")) {
            store.capture("one", "1");
            assertThatThrownBy(() -> store.capture("two", "1")).hasMessage("DEPENDENCY_UNAVAILABLE");
            assertThatThrownBy(() -> store.capture("one", "2")).hasMessage("AUTHENTICATION_FAILED");
            assertThatThrownBy(() -> store.credentials("1")).hasMessage("AUTHENTICATION_FAILED");
            when(auth.resolvePrincipal("private-value")).thenThrow(new IllegalStateException("private-value endpoint"));
            assertThatThrownBy(() -> store.credentials("1")).hasMessage("DEPENDENCY_UNAVAILABLE");
            store.discard("one"); store.capture("two", "1");
        }
        assertThatThrownBy(() -> store.credentials("1")).hasMessage("AUTHENTICATION_REQUIRED");
    }
    @Test void explicitWorkerSnapshotRevalidatesAndClearsThreadCredential() throws Exception {
        var store = store();
        when(auth.resolvePrincipal("worker-token")).thenReturn(new AuthenticatedPrincipal(1L));
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.function.Supplier<AutoCloseable> snapshot;
            try (var ignored = store.openAuthorization("Bearer worker-token")) { snapshot = store.snapshot(); }
            assertThat(worker.submit(() -> { try (var ignored = snapshot.get()) { return store.currentUserId(); } })
                    .get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("1");
            assertThatThrownBy(() -> worker.submit(() -> store.credentials("1")).get())
                    .hasCauseInstanceOf(AgentRagAuthenticationException.class);
            when(auth.resolvePrincipal("worker-token")).thenReturn(null);
            assertThatThrownBy(() -> worker.submit(() -> { try (var ignored = snapshot.get()) { return store.credentials("1"); } }).get())
                    .hasCauseInstanceOf(RagException.class);
        } finally { worker.shutdownNow(); }
    }
    @Test void malformedOrOversizedHeaderIsSafe() {
        var store = store();
        for (String value : List.of("Basic private-value", "Bearer ", "Bearer " + "x".repeat(16385)))
            assertThatThrownBy(() -> store.openAuthorization(value)).hasMessage("AUTHENTICATION_REQUIRED");
    }
    @Test void recursiveCredentialContextAndNonBooleanPolicyAreRejectedBeforeSerialization() throws Exception {
        for (Map<String, Object> context : List.of(Map.<String, Object>of("allowKnowledgeSearch", "false"),
                Map.<String, Object>of("metadata", List.of(Map.of("Authorization", "private-value"))),
                Map.<String, Object>of("nested", Map.of("access_token", "private-value")))) {
            assertThatThrownBy(() -> request(context)).isInstanceOf(IllegalArgumentException.class);
        }
        var request = request(Map.of("collectionName", "agent_knowledge", "allowKnowledgeSearch", false, "topK", 5));
        assertThat(request.context()).containsEntry("spaceId", "supervisor.knowledge").doesNotContainKey("collectionName");
        assertThat(new ObjectMapper().writeValueAsString(request)).doesNotContain("private-value");
    }
    private SupervisorTaskRequest request(Map<String, Object> context) { return new SupervisorTaskRequest("session", "1", "run", "trace", "hello", AgentRagContextPolicy.normalize(context), "api", false); }
    private static class MutableClock extends Clock {
        long millis = 1_000_000;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(millis); }
        public long millis() { return millis; }
    }
}
