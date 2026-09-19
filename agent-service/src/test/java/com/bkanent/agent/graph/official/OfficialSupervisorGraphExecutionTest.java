package com.bkanent.agent.graph.official;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.bkanent.agent.graph.CompletionSubgraph;
import com.bkanent.agent.graph.SingleAgentSubgraph;
import com.bkanent.agent.graph.SupervisorGraphPlanner;
import com.bkanent.agent.graph.SupervisorGraphState;
import com.bkanent.agent.graph.node.BuildApprovalRequestNode;
import com.bkanent.agent.graph.node.BuildNextAgentContextNode;
import com.bkanent.agent.graph.node.HandoffNode;
import com.bkanent.agent.graph.node.MergeParallelResultNode;
import com.bkanent.agent.graph.node.ParallelInvokeNode;
import com.bkanent.agent.graph.node.PersistParallelArtifactsNode;
import com.bkanent.agent.graph.node.RouteDecisionNode;
import com.bkanent.agent.mapper.AgentWorkflowCheckpointMapper;
import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.agent.model.distributed.SupervisorTaskResponse;
import com.bkanent.agent.registry.AgentRegistry;
import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import com.bkanent.agent.stream.SessionStreamService;
import com.bkanent.agent.workflow.SupervisorWorkflowState;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.bkanent.common.agent.AgentCard;
import com.bkanent.common.agent.ApprovalDecision;
import com.bkanent.common.agent.ApprovalRequest;
import com.bkanent.common.agent.ApprovalStatus;
import com.bkanent.common.agent.WorkflowStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OfficialSupervisorGraphExecutionTest {

    private final AgentWorkflowCheckpointMapper checkpointMapper = mock(AgentWorkflowCheckpointMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void configureCheckpointMapper() {
        when(checkpointMapper.selectList(any())).thenReturn(List.of());
        when(checkpointMapper.selectOne(any())).thenReturn(null);
    }

    @Test
    void failedSingleAgentMustEnterFailInsteadOfCompletion() throws Exception {
        SupervisorGraphPlanner planner = mock(SupervisorGraphPlanner.class);
        SingleAgentSubgraph single = mock(SingleAgentSubgraph.class);
        CompletionSubgraph completion = mock(CompletionSubgraph.class);
        SupervisorGraphState planned = plannedState(false, false, "listing", "listing-agent", "failed-single-task");
        AgentTaskInvokeResponse failed = response("FAILED", "child agent failed", List.of());
        when(planner.plan(any(), anyString(), anyString(), anyString())).thenReturn(planned);
        when(single.execute(any(), any(), any())).thenReturn(new SingleAgentSubgraph.ExecutionResult(
                failed, workflowState(planned, failed, "listing-agent")));

        CompiledGraph graph = graph(planner, single, completion, mock(HandoffNode.class));
        OverAllState output = graph.invoke(initialRequest("failed-single-task"), config("failed-single-task")).orElseThrow();

        assertThat(output.value(OfficialSupervisorGraphKeys.WORKFLOW_STATUS, String.class).orElseThrow())
                .isEqualTo(WorkflowStatus.FAILED.name());
        assertThat(output.value(OfficialSupervisorGraphKeys.ERROR_CODE, String.class).orElseThrow())
                .isEqualTo("SINGLE_AGENT_FAILED");
        verify(completion, never()).execute(any());
    }

    @Test
    void nextHintMustUseGraphOwnedHandoffBeforeCompletion() throws Exception {
        SupervisorGraphPlanner planner = mock(SupervisorGraphPlanner.class);
        SingleAgentSubgraph single = mock(SingleAgentSubgraph.class);
        CompletionSubgraph completion = mock(CompletionSubgraph.class);
        HandoffNode handoff = mock(HandoffNode.class);
        BuildNextAgentContextNode contextNode = mock(BuildNextAgentContextNode.class);
        SupervisorGraphState planned = plannedState(false, false, "listing", "listing-agent", "handoff-task");
        AgentTaskInvokeResponse first = response("COMPLETED", "draft ready",
                List.of("marketing.publish_prepare"));
        AgentTaskInvokeResponse second = response("COMPLETED", "publish prepared", List.of());
        SupervisorWorkflowState nextState = workflowState(
                planned.withIntent("marketing.publish_prepare", "marketing", "single_agent")
                        .withSelectedAgent("marketing-agent"), second, "marketing-agent");
        when(planner.plan(any(), anyString(), anyString(), anyString())).thenReturn(planned);
        when(single.execute(any(), any(), any())).thenReturn(new SingleAgentSubgraph.ExecutionResult(
                first, workflowState(planned, first, "listing-agent")));
        when(contextNode.build(any(), eq("marketing"))).thenReturn(Map.of("domain", "marketing"));
        when(handoff.handoff(any(SupervisorWorkflowState.class), eq("marketing"), anyMap(),
                eq("next_hint"), anyInt())).thenReturn(nextState);
        when(completion.execute(any())).thenReturn(new SupervisorTaskResponse(
                "session-1", "handoff-task", "COMPLETED", "done", List.of(), "trace-1",
                "marketing-agent", Map.of()));

        CompiledGraph graph = graph(planner, single, completion, handoff, contextNode);
        OverAllState output = graph.invoke(initialRequest("handoff-task"), config("handoff-task")).orElseThrow();

        assertThat(output.value(OfficialSupervisorGraphKeys.WORKFLOW_STATUS, String.class).orElseThrow())
                .isEqualTo(WorkflowStatus.COMPLETED.name());
        assertThat(output.value(OfficialSupervisorGraphKeys.HANDOFF_COUNT, Integer.class).orElseThrow())
                .isEqualTo(1);
        verify(handoff).handoff(any(SupervisorWorkflowState.class), eq("marketing"), anyMap(),
                eq("next_hint"), anyInt());
        verify(completion).execute(any());
    }

    @Test
    void approvalRejectionBeyondLimitMustFailInsideGraph() throws Exception {
        SupervisorGraphPlanner planner = mock(SupervisorGraphPlanner.class);
        SingleAgentSubgraph single = mock(SingleAgentSubgraph.class);
        CompletionSubgraph completion = mock(CompletionSubgraph.class);
        BuildApprovalRequestNode approvalNode = mock(BuildApprovalRequestNode.class);
        SupervisorGraphState planned = plannedState(false, true, "listing", "listing-agent", "approval-task");
        ApprovalRequest approval = new ApprovalRequest(
                "approval-1", "approval-task", "session-1", "review", "agent-output", "approval-task",
                1, "Review", "review", Map.of(), OfficialSupervisorGraphNodeNames.SINGLE_AGENT,
                "regenerate", "cancel", 0, 0, "trace-1");
        when(planner.plan(any(), anyString(), anyString(), anyString())).thenReturn(planned);
        when(approvalNode.build(any(), anyMap())).thenReturn(approval);

        CompiledGraph graph = graph(planner, single, completion, mock(HandoffNode.class),
                mock(BuildNextAgentContextNode.class), approvalNode);
        RunnableConfig config = config("approval-task");
        OverAllState waiting = graph.invoke(initialRequest("approval-task"), config).orElseThrow();
        assertThat(waiting.value(OfficialSupervisorGraphKeys.WORKFLOW_STATUS, String.class).orElseThrow())
                .isEqualTo(WorkflowStatus.WAITING_USER_APPROVAL.name());

        StateSnapshot snapshot = graph.lastStateOf(config).orElseThrow();
        RunnableConfig updated = graph.updateState(snapshot.config(), Map.of(
                OfficialSupervisorGraphKeys.LATEST_APPROVAL_DECISION,
                new ApprovalDecision("approval-1", ApprovalStatus.REJECTED, "reviewer", "again",
                        LocalDateTime.now(), "trace-1")
        ));
        OverAllState failed = graph.invoke(
                Map.of(),
                updated.withResume()
        ).orElseThrow();

        assertThat(failed.value(OfficialSupervisorGraphKeys.WORKFLOW_STATUS, String.class).orElseThrow())
                .isEqualTo(WorkflowStatus.FAILED.name());
        assertThat(failed.value(OfficialSupervisorGraphKeys.ERROR_CODE, String.class).orElseThrow())
                .isEqualTo("APPROVAL_RETRY_LIMIT_EXCEEDED");
        verify(single, never()).execute(any(), any(), any());
        verify(completion, never()).execute(any());
    }

    @Test
    void approvalApprovalMustResumeIntoTheApprovedAgentBranch() throws Exception {
        SupervisorGraphPlanner planner = mock(SupervisorGraphPlanner.class);
        SingleAgentSubgraph single = mock(SingleAgentSubgraph.class);
        CompletionSubgraph completion = mock(CompletionSubgraph.class);
        BuildApprovalRequestNode approvalNode = mock(BuildApprovalRequestNode.class);
        SupervisorGraphState planned = plannedState(false, true, "listing", "listing-agent", "approved-task");
        ApprovalRequest approval = new ApprovalRequest(
                "approval-approved", "approved-task", "session-1", "review", "agent-output", "approved-task",
                1, "Review", "review", Map.of(), OfficialSupervisorGraphNodeNames.SINGLE_AGENT,
                "regenerate", "cancel", 0, 3, "trace-1");
        AgentTaskInvokeResponse response = response("COMPLETED", "approved result", List.of());
        when(planner.plan(any(), anyString(), anyString(), anyString())).thenReturn(planned);
        when(approvalNode.build(any(), anyMap())).thenReturn(approval);
        when(single.execute(any(), any(), any())).thenReturn(new SingleAgentSubgraph.ExecutionResult(
                response, workflowState(planned, response, "listing-agent")));
        when(completion.execute(any())).thenReturn(new SupervisorTaskResponse(
                "session-1", "approved-task", WorkflowStatus.COMPLETED.name(), "done", List.of(),
                "trace-1", "listing-agent", Map.of()));

        CompiledGraph graph = graph(planner, single, completion, mock(HandoffNode.class),
                mock(BuildNextAgentContextNode.class), approvalNode);
        RunnableConfig config = config("approved-task");
        OverAllState waiting = graph.invoke(initialRequest("approved-task"), config).orElseThrow();
        assertThat(waiting.value(OfficialSupervisorGraphKeys.WORKFLOW_STATUS, String.class).orElseThrow())
                .isEqualTo(WorkflowStatus.WAITING_USER_APPROVAL.name());

        StateSnapshot snapshot = graph.lastStateOf(config).orElseThrow();
        RunnableConfig updated = graph.updateState(snapshot.config(), Map.of(
                OfficialSupervisorGraphKeys.LATEST_APPROVAL_DECISION,
                new ApprovalDecision("approval-approved", ApprovalStatus.APPROVED, "reviewer", "approved",
                        LocalDateTime.now(), "trace-1")
        ));
        OverAllState completed = graph.invoke(Map.of(), updated.withResume()).orElseThrow();

        assertThat(completed.value(OfficialSupervisorGraphKeys.WORKFLOW_STATUS, String.class).orElseThrow())
                .isEqualTo(WorkflowStatus.COMPLETED.name());
        verify(single).execute(any(), any(), any());
        verify(completion).execute(any());
    }

    private CompiledGraph graph(SupervisorGraphPlanner planner,
                                SingleAgentSubgraph single,
                                CompletionSubgraph completion,
                                HandoffNode handoff,
                                Object... extra) throws Exception {
        BuildNextAgentContextNode contextNode = extra.length > 0 && extra[0] instanceof BuildNextAgentContextNode
                ? (BuildNextAgentContextNode) extra[0] : mock(BuildNextAgentContextNode.class);
        BuildApprovalRequestNode approvalNode = extra.length > 1 && extra[1] instanceof BuildApprovalRequestNode
                ? (BuildApprovalRequestNode) extra[1] : mock(BuildApprovalRequestNode.class);
        OfficialSupervisorGraphFactory factory = new OfficialSupervisorGraphFactory(
                new OfficialSupervisorGraphSchema(),
                new DatabaseCheckpointSaverFactory(checkpointMapper, objectMapper),
                planner,
                single,
                completion,
                approvalNode,
                mock(ParallelInvokeNode.class),
                mock(MergeParallelResultNode.class),
                mock(PersistParallelArtifactsNode.class),
                registry(),
                objectMapper,
                mock(SessionStreamService.class),
                contextNode,
                handoff,
                mock(RouteDecisionNode.class)
        );
        return factory.create();
    }

    private AgentRegistry registry() {
        AgentRegistry registry = mock(AgentRegistry.class);
        RegisteredAgentDescriptor listing = descriptor("listing-agent", "listing");
        RegisteredAgentDescriptor marketing = descriptor("marketing-agent", "marketing");
        when(registry.getByAgentId("listing-agent")).thenReturn(Optional.of(listing));
        when(registry.getByAgentId("marketing-agent")).thenReturn(Optional.of(marketing));
        when(registry.findByDomain(anyString())).thenReturn(List.of(listing));
        return registry;
    }

    private RegisteredAgentDescriptor descriptor(String agentId, String domain) {
        return new RegisteredAgentDescriptor(agentId, "http://localhost", "/.well-known/agent.json",
                "/a2a", com.bkanent.agent.registry.AgentRuntimeType.ALIBABA_A2A,
                com.bkanent.agent.registry.AgentDescriptorSource.DISCOVERED_CARD,
                new AgentCard(agentId, agentId, agentId, "1", List.of(), List.of(domain),
                        false, false, "http://localhost/a2a", List.of("text"), List.of("text")));
    }

    private SupervisorGraphState plannedState(boolean parallel,
                                               boolean approval,
                                               String domain,
                                               String agentId,
                                               String taskId) {
        return SupervisorGraphState.initialize("session-1", taskId, "trace-1", "user-1", "query")
                .withIntent("listing.search", domain, parallel ? "parallel" : "single_agent")
                .withPlan(parallel, approval, parallel ? List.of("listing", "marketing") : List.of())
                .withSelectedAgent(agentId);
    }

    private SupervisorWorkflowState workflowState(SupervisorGraphState state,
                                                   AgentTaskInvokeResponse response,
                                                   String agentId) {
        return new SupervisorWorkflowState(
                state.sessionId(), state.taskId(), state.traceId(), state.userId(), state.userMessage(),
                WorkflowStatus.RUNNING, agentId, state.sharedContext(), state.handoffHistory(),
                response.artifactIds(), response, null, null, null);
    }

    private AgentTaskInvokeResponse response(String status, String summary, List<String> nextHints) {
        return new AgentTaskInvokeResponse("session-1", "task-1", "agent", status,
                Map.of("contentType", "agent_output"), List.of(), nextHints, summary, "trace-1");
    }

    private Map<String, Object> initialRequest(String taskId) {
        return new DefaultOfficialSupervisorGraphMigrationFacade(new OfficialSupervisorGraphThreadResolver())
                .initializeState(new SupervisorTaskRequest(
                        "session-1", "user-1", taskId, "trace-1", "query", Map.of(), null, false),
                        "session-1", taskId, "trace-1");
    }

    private RunnableConfig config(String taskId) {
        return new OfficialSupervisorGraphThreadResolver().resolve("session-1", taskId);
    }
}
