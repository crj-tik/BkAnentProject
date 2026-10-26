package com.bkanent.agent.security;

import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.common.rpc.AuthPrincipalContext;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentPrincipalContextTest {

    @Test
    void bindsSupervisorIdentityAndContextToGatewayPrincipal() {
        AgentPrincipalContext principal = principal("42");
        SupervisorTaskRequest request = new SupervisorTaskRequest(
                null, null, "task", "trace", "hello", Map.of("userId", "999", "key", "value"), "web", true);

        SupervisorTaskRequest bound = principal.bind(request);

        assertThat(bound.userId()).isEqualTo("42");
        assertThat(bound.sessionId()).isNotBlank();
        assertThat(bound.context()).containsEntry("userId", "42").containsEntry("key", "value");
    }

    @Test
    void rejectsCallerSuppliedDifferentUserId() {
        AgentPrincipalContext principal = principal("42");

        assertThatThrownBy(() -> principal.resolveUserId("999"))
                .isInstanceOf(AgentPrincipalException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void rejectsPrincipalHeadersNotMarkedAsGatewayInjected() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(AuthPrincipalContext.USER_ID_HEADER, "42");
        request.addHeader(AuthPrincipalContext.MARKER_HEADER, "client");

        assertThatThrownBy(() -> new AgentPrincipalContext(request, org.mockito.Mockito.mock(com.bkanent.common.rpc.AuthPermissionRpcService.class)))
                .isInstanceOf(AgentPrincipalException.class)
                .hasMessageContaining("gateway principal");
    }

    @Test
    void gatewayMarkerAloneCannotAuthenticateAndTokenMustMatchOwner() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(AuthPrincipalContext.USER_ID_HEADER, "42");
        request.addHeader(AuthPrincipalContext.MARKER_HEADER, AuthPrincipalContext.MARKER_VALUE);
        var auth = org.mockito.Mockito.mock(com.bkanent.common.rpc.AuthPermissionRpcService.class);
        assertThatThrownBy(() -> new AgentPrincipalContext(request, auth)).isInstanceOf(AgentPrincipalException.class);
        request.addHeader("Authorization", "Bearer other-token");
        org.mockito.Mockito.when(auth.resolvePrincipal("other-token")).thenReturn(new com.bkanent.common.rpc.AuthenticatedPrincipal(7L));
        assertThatThrownBy(() -> new AgentPrincipalContext(request, auth)).isInstanceOf(AgentPrincipalException.class);
        org.mockito.Mockito.when(auth.resolvePrincipal("other-token")).thenReturn(null);
        assertThatThrownBy(() -> new AgentPrincipalContext(request, auth)).isInstanceOf(AgentPrincipalException.class);
    }

    @Test
    void separateHttpRequestScopesReuseOmittedSessionForAllFourSupervisorSubmissionPaths() throws Exception {
        try (var context = new org.springframework.web.context.support.AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new org.springframework.mock.web.MockServletContext());
            context.register(SubmissionConfiguration.class);
            context.refresh();
            var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context).build();
            var store = context.getBean(com.bkanent.agent.orchestration.OrchestrationStore.class);
            var createdPrincipals = context.getBean(java.util.concurrent.atomic.AtomicInteger.class);
            for (String path : java.util.List.of("tasks", "tasks/async", "workflows", "workflows/async")) {
                String requestId = path.replace('/', '-');
                String body = "{\"requestId\":\"" + requestId + "\",\"userMessage\":\"hello\"}";
                mvc.perform(submission(path, body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.success").value(true));
                String originalSessionId = store.originalRequest(requestId).sessionId();
                assertThat(originalSessionId).isNotBlank();
                mvc.perform(submission(path, body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.success").value(true));
                assertThat(store.originalRequest(requestId).sessionId()).isEqualTo(originalSessionId);
                mvc.perform(submission(path, "{\"requestId\":\"" + requestId + "\",\"sessionId\":\"different-session\",\"userMessage\":\"hello\"}"))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("RUN_REQUEST_ID_CONFLICT"));
                mvc.perform(submission(path, "{\"requestId\":\"" + requestId + "\",\"userMessage\":\"changed\"}"))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
                assertThat(store.originalRequest(requestId).sessionId()).isEqualTo(originalSessionId);
                assertThat(store.originalRequest(requestId).userMessage()).isEqualTo("hello");
            }
            // Each HTTP request constructs a fresh, authenticated @RequestScope principal, never a mocked bind.
            assertThat(createdPrincipals.get()).isEqualTo(16);
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder submission(String path, String body) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/agent/supervisor/" + path)
                .header(AuthPrincipalContext.USER_ID_HEADER, "42")
                .header(AuthPrincipalContext.MARKER_HEADER, AuthPrincipalContext.MARKER_VALUE)
                .header("Authorization", "Bearer real-token").contentType("application/json").content(body);
    }

    @org.springframework.context.annotation.Configuration
    @org.springframework.web.servlet.config.annotation.EnableWebMvc
    static class SubmissionConfiguration {
        @org.springframework.context.annotation.Bean
        java.util.concurrent.atomic.AtomicInteger createdPrincipals() { return new java.util.concurrent.atomic.AtomicInteger(); }

        @org.springframework.context.annotation.Bean
        com.bkanent.common.rpc.AuthPermissionRpcService auth() {
            var auth = org.mockito.Mockito.mock(com.bkanent.common.rpc.AuthPermissionRpcService.class);
            org.mockito.Mockito.when(auth.resolvePrincipal("real-token")).thenReturn(new com.bkanent.common.rpc.AuthenticatedPrincipal(42L));
            return auth;
        }

        @org.springframework.context.annotation.Bean
        com.bkanent.agent.orchestration.OrchestrationStore store() throws Exception {
            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    "jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
            java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
            while (!java.nio.file.Files.exists(root.resolve("sql/migrations/20261004_supervisor_orchestration.sql"))) root = root.getParent();
            var ddl = java.util.regex.Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS agent_orchestration_run \\(.*?;")
                    .matcher(java.nio.file.Files.readString(root.resolve("sql/migrations/20261004_supervisor_orchestration.sql")));
            if (!ddl.find()) throw new IllegalStateException("production run schema missing");
            jdbc.execute(ddl.group());
            return new com.bkanent.agent.orchestration.OrchestrationStore(jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
        }

        @org.springframework.context.annotation.Bean
        @org.springframework.web.context.annotation.RequestScope
        AgentPrincipalContext principal(jakarta.servlet.http.HttpServletRequest request,
                                         com.bkanent.common.rpc.AuthPermissionRpcService auth,
                                         com.bkanent.agent.orchestration.OrchestrationStore store,
                                         java.util.concurrent.atomic.AtomicInteger createdPrincipals) {
            createdPrincipals.incrementAndGet();
            var principal = new AgentPrincipalContext(request, auth);
            principal.setOrchestrationStore(store);
            return principal;
        }

        @org.springframework.context.annotation.Bean
        com.bkanent.agent.security.AgentRagCredentialLeaseStore ragCredentials(
                org.springframework.beans.factory.ObjectProvider<com.bkanent.common.rpc.AuthPermissionRpcService> auth) {
            return new com.bkanent.agent.security.AgentRagCredentialLeaseStore(
                    new com.bkanent.agent.config.AgentRagConsumerProperties(), auth);
        }

        @org.springframework.context.annotation.Bean
        com.bkanent.agent.orchestration.SupervisorToolLoopRunner runner(com.bkanent.agent.orchestration.OrchestrationStore store) {
            return new com.bkanent.agent.orchestration.SupervisorToolLoopRunner(
                    org.mockito.Mockito.mock(com.bkanent.agent.orchestration.SupervisorToolLoopGraph.class),
                    org.mockito.Mockito.mock(com.alibaba.cloud.ai.graph.CompiledGraph.class), store,
                    new com.bkanent.agent.orchestration.SupervisorOrchestrationProperties(),
                    org.mockito.Mockito.mock(com.bkanent.agent.graph.official.ApprovalResumeClaimStore.class));
        }

        @org.springframework.context.annotation.Bean
        com.bkanent.agent.authorization.AgentExecutionBindingCoordinator executionBinding() {
            return org.mockito.Mockito.mock(com.bkanent.agent.authorization.AgentExecutionBindingCoordinator.class);
        }

        @org.springframework.context.annotation.Bean
        com.bkanent.agent.config.AgentChatProperties chatProperties() {
            return new com.bkanent.agent.config.AgentChatProperties();
        }

        @org.springframework.context.annotation.Bean
        com.bkanent.agent.controller.AgentController controller(AgentPrincipalContext principal,
                                                               com.bkanent.agent.orchestration.OrchestrationStore store) {
            var tasks = org.mockito.Mockito.mock(com.bkanent.agent.service.SupervisorTaskService.class);
            var asyncTasks = org.mockito.Mockito.mock(com.bkanent.agent.service.SupervisorAsyncTaskService.class);
            var workflows = org.mockito.Mockito.mock(com.bkanent.agent.service.SupervisorWorkflowService.class);
            var asyncWorkflows = org.mockito.Mockito.mock(com.bkanent.agent.service.SupervisorAsyncWorkflowService.class);
            org.mockito.stubbing.Answer<Object> persist = call -> {
                SupervisorTaskRequest request = call.getArgument(0);
                store.register(request.requestId(), request);
                return null;
            };
            org.mockito.Mockito.when(tasks.submitTask(org.mockito.ArgumentMatchers.any())).thenAnswer(persist);
            org.mockito.Mockito.when(asyncTasks.submitTask(org.mockito.ArgumentMatchers.any())).thenAnswer(persist);
            org.mockito.Mockito.when(workflows.startWorkflow(org.mockito.ArgumentMatchers.any())).thenAnswer(persist);
            org.mockito.Mockito.when(asyncWorkflows.submitWorkflow(org.mockito.ArgumentMatchers.any())).thenAnswer(persist);
            var governance = org.mockito.Mockito.mock(com.bkanent.agent.service.SupervisorGovernanceService.class);
            org.mockito.Mockito.when(governance.applyGrayContext(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> call.getArgument(0));
            return new com.bkanent.agent.controller.AgentController(null, null, null, null, null, null, null,
                    tasks, asyncTasks, asyncWorkflows, workflows, null, null, null, null, null, null,
                    null, governance, null, principal, org.mockito.Mockito.mock(com.bkanent.agent.service.SupervisorSessionOwnershipService.class));
        }
    }

    private AgentPrincipalContext principal(String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(AuthPrincipalContext.USER_ID_HEADER, userId);
        request.addHeader(AuthPrincipalContext.MARKER_HEADER, AuthPrincipalContext.MARKER_VALUE);
        request.addHeader("Authorization", "Bearer real-token");
        var auth = org.mockito.Mockito.mock(com.bkanent.common.rpc.AuthPermissionRpcService.class);
        org.mockito.Mockito.when(auth.resolvePrincipal("real-token")).thenReturn(
                new com.bkanent.common.rpc.AuthenticatedPrincipal(Long.parseLong(userId)));
        return new AgentPrincipalContext(request, auth);
    }
}
