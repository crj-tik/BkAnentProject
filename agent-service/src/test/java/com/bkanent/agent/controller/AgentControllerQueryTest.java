package com.bkanent.agent.controller;

import com.bkanent.agent.security.AgentPrincipalContext;
import com.bkanent.agent.service.AgentPermissionService;
import com.bkanent.agent.service.SupervisorApprovalTodoService;
import com.bkanent.agent.service.SupervisorSessionOwnershipService;
import com.bkanent.agent.service.SupervisorWorkflowQueryService;
import com.bkanent.agent.stream.SessionStreamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentControllerQueryTest {

    private final AgentPrincipalContext principal = mock(AgentPrincipalContext.class);
    private final SupervisorWorkflowQueryService queryService = mock(SupervisorWorkflowQueryService.class);
    private final SupervisorApprovalTodoService approvalTodoService = mock(SupervisorApprovalTodoService.class);
    private final AgentPermissionService permissionService = mock(AgentPermissionService.class);
    private final SessionStreamService streamService = mock(SessionStreamService.class);
    private final SupervisorSessionOwnershipService ownership = mock(SupervisorSessionOwnershipService.class);
    private final com.bkanent.agent.milvus.memory.AgentMemoryMilvusService rag = mock(com.bkanent.agent.milvus.memory.AgentMemoryMilvusService.class);
    private final com.bkanent.agent.milvus.listing.ListingMilvusService listing = mock(com.bkanent.agent.milvus.listing.ListingMilvusService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        AgentController controller = new AgentController(rag, listing, null, null, null, null, null,
                null, null, null, null, queryService, approvalTodoService, null, null, null, streamService,
                permissionService, null, null, principal, ownership);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(principal.resolveUserId("2")).thenReturn("2");
        when(principal.userId()).thenReturn("2");
    }

    @Test
    void actualSixOldMethodPathsKeepSuccessShapesAndInitDimension() throws Exception {
        when(rag.searchKnowledge("2", null, "query", 5)).thenReturn(List.of());
        for (String prefix : List.of("/vector-store", "/mcp")) {
            mvc.perform(get("/agent" + prefix + "/milvus/search").param("userId", "2").param("query", "query"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data").isArray());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/agent" + prefix + "/milvus/collections/init")
                            .param("userId", "2").contentType("application/json").content("{\"collectionName\":\"agent_knowledge\",\"dimension\":1024}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        }
        verify(rag, org.mockito.Mockito.times(2)).initializeMemoryCollection("2", new com.bkanent.agent.milvus.core.model.MilvusCollectionInitRequest("agent_knowledge", 1024));
        when(principal.bind(org.mockito.ArgumentMatchers.any(com.bkanent.agent.model.rag.ListingIndexRequest.class))).thenAnswer(call -> call.getArgument(0));
        when(principal.bind(org.mockito.ArgumentMatchers.any(com.bkanent.agent.model.rag.ListingRagQueryRequest.class))).thenAnswer(call -> call.getArgument(0));
        when(listing.query(org.mockito.ArgumentMatchers.any())).thenReturn(new com.bkanent.agent.model.rag.ListingRagResponse("query", "assembled", List.of()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/agent/rag/listings/index")
                        .contentType("application/json").content("{\"userId\":\"2\",\"listingId\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/agent/rag/listings/query")
                        .contentType("application/json").content("{\"userId\":\"2\",\"query\":\"query\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.query").value("query"))
                .andExpect(jsonPath("$.data.assembledContext").value("assembled")).andExpect(jsonPath("$.data.matches").isArray());
    }

    @Test
    void ragDependencyTimeoutPermissionAndMissingCredentialsRemainDistinct() throws Exception {
        for (var code : List.of(com.bkanent.common.rag.RagErrorCode.DEPENDENCY_UNAVAILABLE, com.bkanent.common.rag.RagErrorCode.TIMEOUT,
                com.bkanent.common.rag.RagErrorCode.FORBIDDEN, com.bkanent.common.rag.RagErrorCode.INVALID_ARGUMENT)) {
            org.mockito.Mockito.doThrow(new com.bkanent.common.rag.RagException(code)).when(rag).searchKnowledge("2", null, "query", 5);
            mvc.perform(get("/agent/mcp/milvus/search").param("userId", "2").param("query", "query"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.code").value(code.name()));
        }
        org.mockito.Mockito.doThrow(new com.bkanent.agent.security.AgentRagAuthenticationException("AUTHENTICATION_REQUIRED")).when(rag).searchKnowledge("2", null, "query", 5);
        mvc.perform(get("/agent/mcp/milvus/search").param("userId", "2").param("query", "query"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void bindsWorkflowStateQueryWithoutCompilerParameterNames() throws Exception {
        when(queryService.findWorkflow("task-1", "2")).thenReturn(Optional.empty());
        mvc.perform(get("/agent/supervisor/workflows/state")
                        .param("taskId", "task-1").param("userId", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(queryService).findWorkflow("task-1", "2");
    }

    @Test
    void bindsArtifactQueryWithoutCompilerParameterNames() throws Exception {
        when(queryService.listArtifacts("task-1", "2")).thenReturn(List.of());
        mvc.perform(get("/agent/supervisor/workflows/artifacts")
                        .param("taskId", "task-1").param("userId", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());
        verify(queryService).listArtifacts("task-1", "2");
    }

    @Test
    void bindsApprovalTodoQueryWithPermissionCheck() throws Exception {
        when(approvalTodoService.listPendingApprovals("2", 1, 20))
                .thenReturn(java.util.Map.of("approvals", List.of(), "total", 0L, "page", 1, "pageSize", 20));
        mvc.perform(get("/agent/supervisor/approvals/pending")
                        .param("userId", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(permissionService).assertCanReadApprovalTodos("2");
        verify(approvalTodoService).listPendingApprovals("2", 1, 20);
    }

    @Test
    void bindsStreamCursorAndChecksSessionOwnership() throws Exception {
        when(streamService.subscribe("session-1", "task-1", "event-9", 9L))
                .thenReturn(new SseEmitter());
        mvc.perform(get("/agent/supervisor/stream").param("sessionId", "session-1")
                        .param("taskId", "task-1").param("afterSequence", "9")
                        .header("Last-Event-ID", "event-9"))
                .andExpect(status().isOk()).andExpect(request().asyncStarted());
        verify(ownership).assertOwned("session-1", "2");
        verify(streamService).subscribe("session-1", "task-1", "event-9", 9L);
    }

    @Test
    void approvalCallbackRequiresAuthenticatedReviewerAndOwnedWorkflowBeforeResuming() throws Exception {
        when(queryService.findWorkflow("task-1", "2")).thenReturn(Optional.empty());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/agent/supervisor/approvals/callback")
                        .contentType("application/json")
                        .content("{\"approvalId\":\"approval\",\"taskId\":\"task-1\",\"status\":\"APPROVED\",\"reviewerId\":\"2\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(false));
        verify(principal).resolveUserId("2");
        verify(queryService).findWorkflow("task-1", "2");
    }
}
