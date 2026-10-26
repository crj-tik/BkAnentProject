package com.bkanent.agent.security;

import com.bkanent.agent.authorization.*;
import com.bkanent.agent.config.*;
import com.bkanent.agent.controller.AgentController;
import com.bkanent.agent.controller.AgentPrincipalExceptionHandler;
import com.bkanent.agent.memory.MemoryStoreClient;
import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.agent.orchestration.*;
import com.bkanent.agent.service.*;
import com.bkanent.common.agent.SessionMemoryResponse;
import com.bkanent.common.authz.*;
import com.bkanent.common.rpc.AuthPermissionRpcService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.annotation.RequestScope;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Actual MVC/parser/request scope/filter, correlated signed Auth stub and durable H2 ownership. */
class AgentExecutionBindingMvcTest {
    private static final long NOW = 1700000000;

    @Test void fourFormalSupervisorEntriesBindOnceBeforeBusinessCaptureAndRestoreBoundAsyncScope() throws Exception {
        for (String path : List.of("/agent/supervisor/tasks", "/agent/supervisor/tasks/async",
                "/agent/supervisor/workflows", "/agent/supervisor/workflows/async")) {
            try (var f = new Fixture()) {
                f.post(path, "{\"userMessage\":\"hello\",\"requestId\":\"run\"}").andExpect(status().isOk());
                verify(f.bindAuth, times(1)).bindExecution(any(), any(), any());
                verify(f.bindAuth, never()).resolveRequestAuthorization(anyString(), any(), any());
                verifyNoInteractions(f.legacyAuth);
                assertThat(f.businessCalls).isEqualTo(1);
                assertThat(f.lastRequest.sessionId()).isNotBlank();
                assertThat(f.originalIngress.context().runId()).isNull();
                assertThat(AgentAuthorizationScope.current()).isNull();
                try (var scope = f.asyncSnapshot.get()) {
                    var entry = AgentAuthorizationScope.current();
                    assertThat(entry.response()).isEqualTo(f.originalIngress);
                    assertThat(entry.session().context().runId()).isEqualTo("run");
                    assertThat(entry.session().context().sessionId()).isEqualTo(f.lastRequest.sessionId());
                    assertThat(f.leases.currentUserId()).isEqualTo("42");
                }
                assertThat(AgentAuthorizationScope.current()).isNull();
                String durable = f.store.json(f.store.originalRequest("run"));
                assertThat(durable).doesNotContain("real-token", "CallerProof", "signature", "AuthorizationSession");
                assertThat(f.consumer.monitor().registry().sessionCount()).isEqualTo(1);
            }
        }
    }

    @Test void omittedSessionRetryRestoresPersistentSessionAndConflictingSessionMessageActorRefuseBeforeBinding() throws Exception {
        try (var f = new Fixture()) {
            String path = "/agent/supervisor/tasks";
            f.post(path, "{\"userMessage\":\"hello\",\"requestId\":\"run\"}").andExpect(status().isOk());
            String session = f.lastRequest.sessionId();
            f.post(path, "{\"userMessage\":\"hello\",\"requestId\":\"run\"}").andExpect(status().isOk());
            assertThat(f.lastRequest.sessionId()).isEqualTo(session);
            verify(f.bindAuth, times(2)).bindExecution(any(), any(), any());
            f.post(path, "{\"userMessage\":\"hello\",\"requestId\":\"run\",\"sessionId\":\"foreign\"}")
                    .andExpect(status().isConflict());
            f.post(path, "{\"userMessage\":\"changed\",\"requestId\":\"run\"}").andExpect(status().isConflict());
            f.post(path, "{\"userId\":\"99\",\"userMessage\":\"hello\",\"requestId\":\"run\"}")
                    .andExpect(status().isForbidden());
            verify(f.bindAuth, times(2)).bindExecution(any(), any(), any());
            assertThat(f.businessCalls).isEqualTo(2);
        }
    }

    @Test void absentRequestIdIsGeneratedOnceAndSameIncomingTupleReusesOneRpc() throws Exception {
        try (var f = new Fixture()) {
            f.post("/agent/supervisor/tasks", "{\"userMessage\":\"hello\"}").andExpect(status().isOk());
            assertThat(f.lastRequest.requestId()).isNotBlank();
            verify(f.bindAuth, times(1)).bindExecution(any(), any(), any());
            try (var scope = f.asyncSnapshot.get()) {
                assertThat(AgentAuthorizationScope.current().normalizeRequestId(null)).isEqualTo(f.lastRequest.requestId());
                assertThat(f.leases.verifiedIdentity("42").principal().userId()).isEqualTo(42L);
            }
            verifyNoInteractions(f.legacyAuth);
        }
    }

    @Test void directHttpWithoutIngressProofPerformsOneIndependentAuthAdmissionAndIgnoresIdentityMarkers() throws Exception {
        try (var f = new Fixture()) {
            f.postDirect("/agent/supervisor/tasks", "{\"userMessage\":\"hello\",\"requestId\":\"run\"}")
                    .andExpect(status().isOk());
            verify(f.bindAuth, times(1)).resolveRequestAuthorization(eq("real-token"), any(), any());
            verify(f.bindAuth, times(1)).bindExecution(any(), any(), any());
            verifyNoInteractions(f.legacyAuth);
            assertThat(f.businessCalls).isEqualTo(1);
            assertThat(f.lastRequest.userId()).isEqualTo("42");
        }
    }

    @Test void directHttpBodyCannotReplaceTheActorAuthenticatedByAuth() throws Exception {
        try (var f = new Fixture()) {
            f.postDirect("/agent/supervisor/tasks", "{\"userId\":\"99\",\"userMessage\":\"hello\",\"requestId\":\"run\"}")
                    .andExpect(status().isForbidden());
            verify(f.bindAuth, times(1)).resolveRequestAuthorization(eq("real-token"), any(), any());
            verify(f.bindAuth, never()).bindExecution(any(), any(), any());
            verifyNoInteractions(f.legacyAuth);
            assertThat(f.businessCalls).isZero();
        }
    }

    @Test void rateLimitedRequestDoesNotCreateAnExecutionBindingAndReleasesItsIngressSession() throws Exception {
        try (var f = new Fixture()) {
            f.rateLimited = true;
            f.post("/agent/supervisor/tasks", "{\"userMessage\":\"hello\",\"requestId\":\"run\"}")
                    .andExpect(status().isOk());
            verify(f.bindAuth, never()).bindExecution(any(), any(), any());
            assertThat(f.businessCalls).isZero();
            assertThat(f.consumer.monitor().registry().sessionCount()).isZero();
        }
    }

    @Test void wrongSignedRunResponseCannotReachBusinessOrCapture() throws Exception {
        try (var f = new Fixture()) {
            f.wrongRun = true;
            f.post("/agent/supervisor/tasks", "{\"userMessage\":\"hello\",\"requestId\":\"run\"}")
                    .andExpect(status().isConflict());
            assertThat(f.businessCalls).isZero();
            assertThat(f.store.existingSession("run", "42")).isNull();
            assertThat(f.consumer.monitor().registry().sessionCount()).isZero();
            assertThat(AgentAuthorizationScope.current()).isNull();
        }
    }

    static final class Fixture implements AutoCloseable {
        final Clock clock = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);
        final KeyPair authKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final KeyPair agentKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final AuthPermissionRpcService legacyAuth = mock(AuthPermissionRpcService.class);
        final AuthAuthorizationRpcService bindAuth = mock(AuthAuthorizationRpcService.class);
        final AgentAuthorizationProperties properties = new AgentAuthorizationProperties();
        final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        final OrchestrationStore store;
        final AgentAuthorizationConsumer consumer;
        final AgentRagCredentialLeaseStore leases;
        final AgentExecutionBindingCoordinator coordinator;
        final SupervisorTaskService tasks = mock(SupervisorTaskService.class);
        final SupervisorAsyncTaskService asyncTasks = mock(SupervisorAsyncTaskService.class);
        final SupervisorWorkflowService workflows = mock(SupervisorWorkflowService.class);
        final SupervisorAsyncWorkflowService asyncWorkflows = mock(SupervisorAsyncWorkflowService.class);
        final SupervisorGovernanceService governance = mock(SupervisorGovernanceService.class);
        final SupervisorToolLoopRunner runner = mock(SupervisorToolLoopRunner.class);
        final SupervisorSessionOwnershipService ownership;
        final Map<String, SessionMemoryResponse> sessions = new HashMap<>();
        final AnnotationConfigWebApplicationContext web = new AnnotationConfigWebApplicationContext();
        Path serviceKeyFile;
        final MockMvc mvc;
        AuthzResponse originalIngress;
        SupervisorTaskRequest lastRequest;
        Supplier<AutoCloseable> asyncSnapshot;
        int businessCalls;
        boolean wrongRun;
        boolean rateLimited;

        Fixture() throws Exception {
            var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
            jdbc.execute("CREATE TABLE agent_orchestration_run(run_id VARCHAR(128) PRIMARY KEY,user_id VARCHAR(128),request_json TEXT,lease_until_ms BIGINT)");
            store = new OrchestrationStore(jdbc, mapper);
            properties.setEnabled(true); properties.setKeyId("agent-key");
            serviceKeyFile = Files.createTempFile("agent-service-test-", ".pem");
            Files.writeString(serviceKeyFile, "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getEncoder().encodeToString(agentKey.getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----\n");
            properties.setPrivateKeyPath(serviceKeyFile.toString());
            properties.setPublicKey(Base64.getEncoder().encodeToString(agentKey.getPublic().getEncoded()));
            properties.setAuthorityPublicKeys(Map.of("auth-key", Base64.getEncoder().encodeToString(authKey.getPublic().getEncoded())));
            var beans = new DefaultListableBeanFactory();
            beans.registerSingleton("auth", legacyAuth); beans.registerSingleton("bindingAuth", bindAuth);
            leases = new AgentRagCredentialLeaseStore(new AgentRagConsumerProperties(), beans.getBeanProvider(AuthPermissionRpcService.class), clock);
            consumer = new AgentAuthorizationConsumer(properties, new AuthorizationCache(30, 1000000, Duration.ofMinutes(5), 2),
                    new AuthorizationVersionMonitor(users -> Map.of(), Map.of("auth-key", authKey.getPublic()), clock), clock);
            beans.registerSingleton("consumer", consumer);
            coordinator = new AgentExecutionBindingCoordinator(properties, beans.getBeanProvider(AuthAuthorizationRpcService.class),
                    beans.getBeanProvider(AgentAuthorizationConsumer.class), store, clock, agentKey.getPrivate());
            var memory = mock(MemoryStoreClient.class);
            when(memory.getSessionMemory(anyString())).thenAnswer(call -> Optional.ofNullable(sessions.get(call.getArgument(0))));
            doAnswer(call -> {
                com.bkanent.common.agent.SessionMemoryUpsertRequest request = call.getArgument(0);
                sessions.put(request.sessionId(), new SessionMemoryResponse(request.sessionId(), request.userId(), Map.of(), null, null, 1));
                return null;
            }).when(memory).upsertSessionMemory(any());
            ownership = new SupervisorSessionOwnershipService(memory);
            when(governance.applyGrayContext(any())).thenAnswer(call -> call.getArgument(0));
            doAnswer(call -> {
                if (rateLimited) throw new IllegalStateException("rate limit exceeded for test");
                return null;
            }).when(governance).assertRateLimit(anyString(), any());
            doAnswer(call -> {
                SupervisorTaskRequest request = call.getArgument(0);
                if (store.existingSession(request.requestId(), request.userId()) != null) store.assertOriginalRequest(request.requestId(), request);
                return null;
            }).when(runner).assertRequestIdentity(any());
            when(bindAuth.bindExecution(any(), any(), any())).thenAnswer(call -> {
                AuthzResponse ingress = call.getArgument(0); ExecutionBinding b = call.getArgument(1); CallerProof proof = call.getArgument(2);
                AuthzSignature.verifyCallerProof(proof, "agent-service", "auth-service", AuthzRpcOperation.BIND_EXECUTION,
                        b.requestId(), AuthzCanonicalHash.executionBindingRequestHash(ingress, b), b.nonce(),
                        Set.of(AuthzRpcOperation.BIND_EXECUTION), Map.of("agent-key", agentKey.getPublic()), clock.instant());
                AuthzSignature.verifyExecutionBinding(ingress, b, Map.of("auth-key", authKey.getPublic()), clock.instant());
                var c = ingress.context();
                var context = AuthzSignature.sign(new AuthorizationContext(c.actorId(), c.permissionRevision(), c.credentialEpoch(),
                        c.permissionHash(), b.operations(), c.audience(), b.requestId(), wrongRun ? "wrong" : b.runId(), b.sessionId(),
                        c.policyDigest(), proof.requestHash(), b.nonce(), NOW, c.expiresAtEpochSecond(), c.runHardDeadlineEpochSecond(),
                        AuthzContract.ALGORITHM, "auth-key", null), authKey.getPrivate());
                return new AuthzResponse(ingress.snapshot(), context, ingress.freshness());
            });
            when(bindAuth.resolveRequestAuthorization(anyString(), any(), any())).thenAnswer(call -> {
                String token = call.getArgument(0); RequestBinding binding = call.getArgument(1); CallerProof proof = call.getArgument(2);
                String requestHash = AuthzCanonicalHash.resolveRequestHash(token, binding);
                AuthzSignature.verifyCallerProof(proof, "agent-service", "auth-service",
                        AuthzRpcOperation.RESOLVE_REQUEST_AUTHORIZATION, binding.requestId(), requestHash,
                        binding.nonce(), Set.of(AuthzRpcOperation.RESOLVE_REQUEST_AUTHORIZATION),
                        Map.of("agent-key", agentKey.getPublic()), clock.instant());
                var snapshot = AuthzSignature.sign(new AuthorizationSnapshot("42", 1, 1, false,
                        List.of("agent.chat.use"), null, AuthzContract.ALGORITHM, "auth-key", null), authKey.getPrivate());
                String permissionHash = AuthzCanonicalHash.snapshotHash(snapshot);
                var context = AuthzSignature.sign(new AuthorizationContext("42", 1, 0, permissionHash,
                        snapshot.permissions(), binding.audience(), binding.requestId(), null, null,
                        binding.policyDigest(), requestHash, binding.nonce(), NOW, NOW + 300, NOW + 300,
                        AuthzContract.ALGORITHM, "auth-key", null), authKey.getPrivate());
                var freshness = AuthzSignature.sign(new AuthzFreshness("42", 1, 0, AuthorizationStatus.ACTIVE,
                        permissionHash, NOW, NOW + 30, binding.nonce(), AuthzContract.ALGORITHM, "auth-key", null), authKey.getPrivate());
                originalIngress = new AuthzResponse(snapshot, context, freshness);
                return originalIngress;
            });
            org.mockito.stubbing.Answer<Object> business = call -> {
                SupervisorTaskRequest request = call.getArgument(0);
                var entry = AgentAuthorizationScope.current(); entry.requireRunAssociation();
                assertThat(entry.session().context().runId()).isEqualTo(request.requestId());
                coordinator.bind(request); // Runner's second admission in the same incoming request is local reuse.
                store.register(request.requestId(), request);
                leases.capture(request.requestId(), request.userId());
                asyncSnapshot = leases.snapshot(); lastRequest = request; businessCalls++;
                return null;
            };
            doAnswer(business).when(tasks).submitTask(any()); doAnswer(business).when(asyncTasks).submitTask(any());
            doAnswer(business).when(workflows).startWorkflow(any()); doAnswer(business).when(asyncWorkflows).submitWorkflow(any());
            web.setServletContext(new MockServletContext());
            web.addBeanFactoryPostProcessor(factory -> {
                factory.registerSingleton("fixture", this);
                factory.registerSingleton("authorizationProperties", properties);
                factory.registerSingleton("executionBindingCoordinator", coordinator);
                factory.registerSingleton("toolLoopRunner", runner);
            });
            web.register(WebConfig.class); web.refresh();
            var filter = new AgentRagCredentialFilter(leases, properties, beans.getBeanProvider(AgentAuthorizationConsumer.class),
                    beans.getBeanProvider(AuthAuthorizationRpcService.class), clock);
            mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web).addFilters(filter).build();
        }

        org.springframework.test.web.servlet.ResultActions post(String path, String json) throws Exception {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
            String requestId = UUID.randomUUID().toString();
            String policy = AuthzCanonicalHash.bodyHash(new byte[0]);
            var binding = new RequestBinding("gateway", "agent-service", "POST", path, path, List.of(), requestId, null, null,
                    AuthzCanonicalHash.bodyHash(body), policy, nonce, NOW, NOW + 60);
            var snapshot = AuthzSignature.sign(new AuthorizationSnapshot("42", 1, 1, false, List.of("agent.chat.use"),
                    null, AuthzContract.ALGORITHM, "auth-key", null), authKey.getPrivate());
            String hash = AuthzCanonicalHash.snapshotHash(snapshot);
            var context = AuthzSignature.sign(new AuthorizationContext("42", 1, 0, hash, snapshot.permissions(), "agent-service",
                    requestId, null, null, policy, AuthzCanonicalHash.resolveRequestHash("real-token", binding), nonce,
                    NOW, NOW + 300, NOW + 300, AuthzContract.ALGORITHM, "auth-key", null), authKey.getPrivate());
            var freshness = AuthzSignature.sign(new AuthzFreshness("42", 1, 0, AuthorizationStatus.ACTIVE, hash,
                    NOW, NOW + 30, nonce, AuthzContract.ALGORITHM, "auth-key", null), authKey.getPrivate());
            originalIngress = new AuthzResponse(snapshot, context, freshness);
            return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).contentType("application/json").content(body).header("Authorization", "Bearer real-token")
                    .header(AuthzTransportHeaders.RESPONSE_HEADER, AuthzWireCodec.encodeResponse(originalIngress))
                    .header(AuthzTransportHeaders.REQUEST_BINDING_HEADER, AuthzWireCodec.encodeBinding(binding)));
        }
        org.springframework.test.web.servlet.ResultActions postDirect(String path, String json) throws Exception {
            return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                    .contentType("application/json").content(json)
                    .header("Authorization", "Bearer real-token")
                    .header(com.bkanent.common.rpc.AuthPrincipalContext.MARKER_HEADER, com.bkanent.common.rpc.AuthPrincipalContext.MARKER_VALUE)
                    .header(com.bkanent.common.rpc.AuthPrincipalContext.USER_ID_HEADER, "99"));
        }
        @Override public void close() throws Exception { web.close(); Files.deleteIfExists(serviceKeyFile); }
    }

    @Configuration @EnableWebMvc static class WebConfig {
        @Bean AuthPermissionRpcService auth(Fixture f) { return f.legacyAuth; }
        @Bean OrchestrationStore store(Fixture f) { return f.store; }
        @Bean @RequestScope AgentPrincipalContext principal(jakarta.servlet.http.HttpServletRequest request, Fixture f) {
            return new AgentPrincipalContext(request, f.legacyAuth, f.properties);
        }
        @Bean AgentPrincipalExceptionHandler errors() { return new AgentPrincipalExceptionHandler(); }
        @Bean AgentController controller(AgentPrincipalContext principal, Fixture f) {
            var controller = new AgentController(null, null, null, null, null, null, null,
                    f.tasks, f.asyncTasks, f.asyncWorkflows, f.workflows, null, null, null, null, null, null,
                    null, f.governance, null, principal, f.ownership);
            controller.setToolLoopRunner(f.runner); controller.setExecutionBinding(f.coordinator);
            return controller;
        }
    }
}
