package com.bkanent.memory.controller;

import com.bkanent.common.agent.SessionMemoryResponse;
import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.MemoryTransportHeaders;
import com.bkanent.memory.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MemoryControllerTest {
    private static final AuthRagPrincipal ADMIN = new AuthRagPrincipal(42L, "ADMIN", true, true, Long.MAX_VALUE);

    private MemoryRagSecurity security() {
        var security = mock(MemoryRagSecurity.class);
        when(security.authenticate(new RagCredentialContext("valid-token"))).thenReturn(ADMIN);
        when(security.authenticate(new RagCredentialContext("revoked-token")))
                .thenThrow(new RagException(RagErrorCode.AUTHENTICATION_FAILED));
        when(security.authenticate(new RagCredentialContext("dependency-token")))
                .thenThrow(new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE));
        doAnswer(call -> {
            if (!"42".equals(call.getArgument(1))) throw new RagException(RagErrorCode.FORBIDDEN);
            return null;
        }).when(security).requireOwner(eq(ADMIN), any());
        return security;
    }

    private MockMvc mvc(MemoryController controller, MemoryRagSecurity security) {
        return MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new MemoryRequestAuthenticationFilter(security))
                .setControllerAdvice(new MemoryRagExceptionHandler()).build();
    }

    @Test
    void preferenceRestAdminStillCannotReadWriteOrDecayAnotherOwner() throws Exception {
        var security = security();
        var preferences = mock(UserPreferenceMemoryService.class);
        var controller = new MemoryController(null, null, null, preferences, null, null, security, null, null);
        var record = new com.bkanent.common.agent.UserPreferenceRecord("43", "key", "private", "category",
                java.math.BigDecimal.ONE, null, null, null, 1);
        assertThrows(RagException.class, () -> controller.getUserPreferences("43", null, "Bearer valid-token"));
        assertThrows(RagException.class, () -> controller.upsertUserPreference(record, "Bearer valid-token"));
        assertThrows(RagException.class, () -> controller.decayUserPreferences("43", null, "Bearer valid-token"));
        assertThrows(RagException.class, () -> controller.getUserPreferences("42", null, null));
        verifyNoInteractions(preferences);
        when(preferences.findByUserId("42", null)).thenReturn(List.of());
        mvc(controller, security).perform(get("/internal/memory/user-preferences/42")
                        .header("Authorization", "Bearer valid-token").header(MemoryTransportHeaders.OWNER, "42"))
                .andExpect(status().isOk());
        verify(preferences).findByUserId("42", null);
    }

    @Test
    void resolvesAuthenticatedSessionAndArtifactParametersWithoutCompilerParameterNames() throws Exception {
        var sessions = mock(SessionSharedMemoryService.class);
        var artifacts = mock(TaskArtifactMemoryService.class);
        var security = security();
        when(sessions.find("session-1")).thenReturn(Optional.of(new SessionMemoryResponse("session-1", "42", Map.of(), null, null, 1)));
        when(artifacts.listByTaskId("task-1", "session-1")).thenReturn(List.of());
        var mvc = mvc(new MemoryController(sessions, artifacts, null, null, null, null, security, null, null), security);
        mvc.perform(get("/internal/memory/sessions").param("sessionId", "session-1")
                        .header("Authorization", "Bearer valid-token").header(MemoryTransportHeaders.OWNER, "42"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        mvc.perform(get("/internal/memory/artifacts/by-task").param("taskId", "task-1").param("sessionId", "session-1")
                        .header("Authorization", "Bearer valid-token").header(MemoryTransportHeaders.OWNER, "42"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());
        verify(sessions, times(2)).find("session-1");
        verify(artifacts).listByTaskId("task-1", "session-1");
    }

    @Test
    void allInternalRoutesRejectMissingRevokedOrMismatchedCredentialsBeforeBusinessEffects() throws Exception {
        var sessions = mock(SessionSharedMemoryService.class);
        var artifacts = mock(TaskArtifactMemoryService.class);
        var handoffs = mock(HandoffRelationMemoryService.class);
        var snapshots = mock(SessionMemorySnapshotService.class);
        var security = security();
        var mvc = mvc(new MemoryController(sessions, artifacts, handoffs, null, null, snapshots, security, null, null), security);
        for (String path : List.of("/internal/memory/sessions?sessionId=s", "/internal/memory/artifacts/by-id?artifactId=a&taskId=t",
                "/internal/memory/handoffs/by-task?taskId=t", "/internal/memory/sessions/snapshot/s", "/internal/memory/workflow/t/history")) {
            mvc.perform(get(path).header("X-Gateway-Authenticated", "true").header(MemoryTransportHeaders.OWNER, "42"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", "Bearer valid-token")).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", "Bearer revoked-token").header(MemoryTransportHeaders.OWNER, "42"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", "Bearer valid-token").header(MemoryTransportHeaders.OWNER, "43"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/internal/memory/sessions").param("sessionId", "s")
                        .header("Authorization", "Bearer dependency-token").header(MemoryTransportHeaders.OWNER, "42"))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(sessions, artifacts, handoffs, snapshots);
    }

    private com.bkanent.common.agent.ArtifactQueryResponse artifact(String session) {
        return new com.bkanent.common.agent.ArtifactQueryResponse(new com.bkanent.common.agent.ArtifactMeta(
                "artifact", "task", session, "agent", "text", 1, Map.of(), null, null), "private content");
    }

    private com.bkanent.common.agent.HandoffRelationQueryResponse handoff(String session, List<String> artifacts) {
        return new com.bkanent.common.agent.HandoffRelationQueryResponse(new com.bkanent.common.agent.AgentHandoffPacket(
                session, "task", null, null, "from", "to", "reason", "goal", Map.of(), artifacts, List.of(), "output"), null);
    }

    @Test
    void optionalArtifactSessionTaskHandoffsAndWorkflowUsePersistedOwnerNotCallerParameters() throws Exception {
        var sessions = mock(SessionSharedMemoryService.class);
        var artifacts = mock(TaskArtifactMemoryService.class);
        var handoffs = mock(HandoffRelationMemoryService.class);
        var security = security();
        when(sessions.find("foreign")).thenReturn(Optional.of(new SessionMemoryResponse("foreign", "43", Map.of(), null, null, 1)));
        when(sessions.find("own")).thenReturn(Optional.of(new SessionMemoryResponse("own", "42", Map.of(), null, null, 1)));
        when(artifacts.getByArtifactId("artifact", "task", null)).thenReturn(artifact("foreign"));
        when(handoffs.listByTaskId("task")).thenReturn(List.of(handoff("foreign", List.of())));
        var mvc = mvc(new MemoryController(sessions, artifacts, handoffs, null, null, null, security, null, null), security);
        for (String path : List.of("/internal/memory/artifacts/by-id?artifactId=artifact&taskId=task",
                "/internal/memory/handoffs/by-task?taskId=task", "/internal/memory/workflow/task/history")) {
            mvc.perform(get(path).header("Authorization", "Bearer valid-token").header(MemoryTransportHeaders.OWNER, "42"))
                    .andExpect(status().isForbidden()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private content"))));
        }
        // A caller-owned packet cannot launder a foreign persisted artifact through history.
        when(handoffs.listByTaskId("task")).thenReturn(List.of(handoff("own", List.of("artifact"))));
        mvc.perform(get("/internal/memory/workflow/task/history")
                        .header("Authorization", "Bearer valid-token").header(MemoryTransportHeaders.OWNER, "42"))
                .andExpect(status().isForbidden());
        when(artifacts.getByArtifactId("artifact", "task", null)).thenReturn(artifact("missing"));
        when(sessions.find("missing")).thenReturn(Optional.empty());
        mvc.perform(get("/internal/memory/artifacts/by-id").param("artifactId", "artifact").param("taskId", "task")
                        .header("Authorization", "Bearer valid-token").header(MemoryTransportHeaders.OWNER, "42"))
                .andExpect(status().isForbidden());
        when(artifacts.getByArtifactId("artifact", "task", null)).thenReturn(artifact("own"));
        mvc.perform(get("/internal/memory/artifacts/by-id").param("artifactId", "artifact").param("taskId", "task")
                        .header("Authorization", "Bearer valid-token").header(MemoryTransportHeaders.OWNER, "42"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.meta.sessionId").value("own"));
        mvc.perform(get("/internal/memory/handoffs/by-task").param("taskId", "task")
                        .header("Authorization", "Bearer valid-token").header(MemoryTransportHeaders.OWNER, "42"))
                .andExpect(status().isOk());
        mvc.perform(get("/internal/memory/workflow/task/history")
                        .header("Authorization", "Bearer valid-token").header(MemoryTransportHeaders.OWNER, "42"))
                .andExpect(status().isOk());
    }

    @Test
    void missingPersistedSessionNeverAuthorizesArtifactHandoffOrSnapshotWrites() throws Exception {
        var sessions = mock(SessionSharedMemoryService.class);
        var artifacts = mock(TaskArtifactMemoryService.class);
        var handoffs = mock(HandoffRelationMemoryService.class);
        var snapshots = mock(SessionMemorySnapshotService.class);
        var security = security();
        when(sessions.find("unestablished")).thenReturn(Optional.empty());
        var mvc = mvc(new MemoryController(sessions, artifacts, handoffs, null, null, snapshots, security, null, null), security);
        for (String path : List.of("/internal/memory/artifacts", "/internal/memory/handoffs", "/internal/memory/sessions/snapshot")) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sessionId\":\"unestablished\",\"taskId\":\"task\",\"memory\":{}}")
                            .header("Authorization", "Bearer valid-token").header(MemoryTransportHeaders.OWNER, "42"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(artifacts, handoffs, snapshots);
    }

    @Test
    void revokedAfterPersistentReadCannotReturnArtifactHandoffOrHistory() throws Exception {
        for (String route : List.of("artifact", "handoff", "history")) {
            var sessions = mock(SessionSharedMemoryService.class);
            var artifacts = mock(TaskArtifactMemoryService.class);
            var handoffs = mock(HandoffRelationMemoryService.class);
            var security = security();
            var revoked = new java.util.concurrent.atomic.AtomicBoolean();
            when(security.authenticate(new RagCredentialContext("valid-token"))).thenAnswer(call -> {
                if (revoked.get()) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
                return ADMIN;
            });
            when(sessions.find("own")).thenReturn(Optional.of(new SessionMemoryResponse("own", "42", Map.of(), null, null, 1)));
            if (route.equals("artifact")) {
                when(artifacts.getByArtifactId("artifact", "task", null)).thenAnswer(call -> { revoked.set(true); return artifact("own"); });
            } else if (route.equals("handoff")) {
                when(handoffs.listByTaskId("task")).thenAnswer(call -> { revoked.set(true); return List.of(); });
            } else {
                when(handoffs.listByTaskId("task")).thenReturn(List.of(handoff("own", List.of("artifact"))));
                when(artifacts.getByArtifactId("artifact", "task", null)).thenAnswer(call -> { revoked.set(true); return artifact("own"); });
            }
            String path = route.equals("artifact") ? "/internal/memory/artifacts/by-id?artifactId=artifact&taskId=task"
                    : route.equals("handoff") ? "/internal/memory/handoffs/by-task?taskId=task" : "/internal/memory/workflow/task/history";
            var mvc = mvc(new MemoryController(sessions, artifacts, handoffs, null, null, null, security, null, null), security);
            mvc.perform(get(path).header("Authorization", "Bearer valid-token").header(MemoryTransportHeaders.OWNER, "42"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private content"))));
        }
    }

    @Test
    void ownerAssertionCannotOverrideBodyOrExistingSessionOwnerEvenForAdmin() throws Exception {
        var sessions = mock(SessionSharedMemoryService.class);
        var artifacts = mock(TaskArtifactMemoryService.class);
        var security = security();
        when(sessions.find("foreign")).thenReturn(Optional.of(new SessionMemoryResponse("foreign", "43", Map.of(), null, null, 1)));
        var mvc = mvc(new MemoryController(sessions, artifacts, null, null, null, null, security, null, null), security);
        mvc.perform(post("/internal/memory/sessions/upsert").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"new\",\"userId\":\"43\",\"memory\":{}}")
                        .header("Authorization", "Bearer valid-token").header(MemoryTransportHeaders.OWNER, "42"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/internal/memory/sessions/upsert").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"foreign\",\"userId\":\"42\",\"memory\":{}}")
                        .header("Authorization", "Bearer valid-token").header(MemoryTransportHeaders.OWNER, "42"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/memory/sessions").param("sessionId", "foreign")
                        .header("Authorization", "Bearer valid-token").header(MemoryTransportHeaders.OWNER, "42"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/internal/memory/artifacts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskId\":\"task\",\"sessionId\":\"foreign\",\"artifactType\":\"text\"}")
                        .header("Authorization", "Bearer valid-token").header(MemoryTransportHeaders.OWNER, "42"))
                .andExpect(status().isForbidden());
        verify(sessions, never()).upsert(any());
        verifyNoInteractions(artifacts);
    }
}
