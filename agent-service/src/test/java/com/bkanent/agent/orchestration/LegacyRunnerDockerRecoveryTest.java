package com.bkanent.agent.orchestration;

import com.bkanent.agent.catalog.DomainCatalog;
import com.bkanent.agent.graph.*;
import com.bkanent.agent.graph.node.*;
import com.bkanent.agent.graph.official.*;
import com.bkanent.agent.mapper.*;
import com.bkanent.agent.model.distributed.*;
import com.bkanent.agent.registry.*;
import com.bkanent.agent.stream.SessionStreamService;
import com.bkanent.agent.workflow.SupervisorWorkflowState;
import com.bkanent.common.agent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual old graph topology/JSON/checkpoint/approval in MySQL, alongside the new entry router. */
class LegacyRunnerDockerRecoveryTest {
    @Test
    void oldApprovalRestartsOnOriginalRunnerAndTargetWhileNewRequestsUseOnlyNewRunner() throws Exception {
        Assumptions.assumeTrue(System.getenv("BK_AGENT_DB_URL") != null, "Docker database not configured");
        var db = new DockerAcceptanceDatabase(); var mapper = new ObjectMapper().findAndRegisterModules();
        var saver = new DatabaseCheckpointSaverFactory(db.session.getMapper(AgentWorkflowCheckpointMapper.class), mapper);
        var claims = new ApprovalResumeClaimStore(db.session.getMapper(AgentWorkflowApprovalClaimMapper.class), mapper);
        var planner = mock(SupervisorGraphPlanner.class); var single = mock(SingleAgentSubgraph.class); var completion = mock(CompletionSubgraph.class);
        var approvals = mock(BuildApprovalRequestNode.class); var registry = mock(AgentRegistry.class); var domains = mock(DomainCatalog.class);
        when(domains.branchCapacity()).thenReturn(16); when(domains.contains("listing")).thenReturn(true);
        when(domains.rewriteHint(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        var selected = new RegisteredAgentDescriptor("legacy-selected-agent", "http://original", "/.well-known/agent.json", "/a2a",
                AgentRuntimeType.ALIBABA_A2A, AgentDescriptorSource.DISCOVERED_CARD,
                new AgentCard("legacy-selected-agent", "legacy", "original target", "0", List.of(), List.of("listing"), false, false,
                        "http://original/a2a", List.of("text"), List.of("text")), Map.of());
        when(registry.getByAgentId("legacy-selected-agent")).thenReturn(Optional.of(selected));
        when(registry.findByDomain("listing")).thenReturn(List.of(selected));
        when(planner.plan(any(), anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            SupervisorTaskRequest request = invocation.getArgument(0);
            return SupervisorGraphState.initialize(request.sessionId(), request.requestId(), request.traceId(), "1", request.userMessage())
                    .withIntent("listing.search", "listing", "single_agent").withPlan(false, true, List.of()).withSelectedAgent("legacy-selected-agent");
        });
        when(approvals.build(any(), anyMap())).thenAnswer(invocation -> {
            SupervisorWorkflowState state = invocation.getArgument(0);
            return new ApprovalRequest("approval-" + state.taskId(), state.taskId(), state.sessionId(), "review", "agent-output", state.taskId(), 1,
                    "旧任务审批", "保持原目标", Map.of(), OfficialSupervisorGraphNodeNames.SINGLE_AGENT, "regenerate", "cancel", 0, 3, state.traceId());
        });
        var effects = new AtomicInteger();
        when(single.execute(any(), any(), eq(selected))).thenAnswer(invocation -> {
            effects.incrementAndGet(); SupervisorGraphState state = invocation.getArgument(1);
            var response = new AgentTaskInvokeResponse(state.sessionId(), state.taskId(), selected.agentId(), "COMPLETED", Map.of("fact", "original"), List.of(), List.of(), "done", state.traceId());
            return new SingleAgentSubgraph.ExecutionResult(response, new SupervisorWorkflowState(state.sessionId(), state.taskId(), state.traceId(), state.userId(),
                    state.userMessage(), WorkflowStatus.RUNNING, selected.agentId(), state.sharedContext(), state.handoffHistory(), List.of(), response, null, null, null));
        });
        when(completion.execute(any())).thenAnswer(invocation -> {
            SupervisorWorkflowState state = invocation.getArgument(0);
            return new SupervisorTaskResponse(state.sessionId(), state.taskId(), "COMPLETED", "original result", List.of(), state.traceId(), selected.agentId(), Map.of());
        });
        var factory = new OfficialSupervisorGraphFactory(new OfficialSupervisorGraphSchema(), saver, planner, single, completion, approvals,
                mock(ParallelInvokeNode.class), mock(MergeParallelResultNode.class), mock(PersistParallelArtifactsNode.class), registry, domains,
                mapper, mock(SessionStreamService.class), mock(BuildNextAgentContextNode.class), mock(HandoffNode.class), mock(RouteDecisionNode.class));
        var migration = new DefaultOfficialSupervisorGraphMigrationFacade(new OfficialSupervisorGraphThreadResolver());
        String run = "legacy-" + UUID.randomUUID();
        var request = new SupervisorTaskRequest("session", "1", run, run, "original request", Map.of(), "api", false);
        var old = new DefaultOfficialSupervisorGraphFacade(new OfficialSupervisorGraphHolder(factory), migration, claims, mapper);
        assertThat(old.execute(request).status()).isEqualTo("WAITING_USER_APPROVAL");
        assertThat(effects).hasValue(0);
        var fresh = new DefaultOfficialSupervisorGraphFacade(new OfficialSupervisorGraphHolder(factory), migration, claims, mapper);
        var newRunner = mock(SupervisorToolLoopRunner.class); fresh.setToolLoopRunner(newRunner);
        assertThat(fresh.execute(request).status()).isEqualTo("WAITING_USER_APPROVAL");
        assertThatThrownBy(() -> fresh.execute(new SupervisorTaskRequest("session", "2", run, run, "forged", Map.of(), "api", false)))
                .hasMessage("LEGACY_RUN_OWNER_MISMATCH");
        var callback = new ApprovalCallbackRequest("approval-" + run, run, "session", ApprovalStatus.APPROVED, "1", null, run, 1);
        assertThat(fresh.resume(callback).status()).isEqualTo("COMPLETED");
        assertThat(fresh.resume(callback).status()).isEqualTo("COMPLETED");
        assertThat(new DefaultOfficialSupervisorGraphFacade(new OfficialSupervisorGraphHolder(factory), migration, claims, mapper).execute(request).status())
                .isEqualTo("COMPLETED");
        assertThat(effects).hasValue(1); verify(newRunner, never()).execute(any()); verify(newRunner, never()).resume(any());
        var next = new SupervisorTaskRequest("session", "1", run + "-new", run, "不要通知，只分析合同", Map.of("domain", "listing"), "api", false);
        when(newRunner.execute(any())).thenReturn(new SupervisorTaskResponse("session", next.requestId(), "COMPLETED", "LLM result", List.of(), run, null, Map.of("runnerVersion", "llm-tools-v1")));
        assertThat(fresh.execute(next).governanceMetadata()).containsEntry("runnerVersion", "llm-tools-v1");
        verify(newRunner).execute(any()); assertThat(effects).hasValue(1);

        String withdrawn = run + "-withdrawn";
        var withdrawnRequest = new SupervisorTaskRequest("session", "1", withdrawn, run, "original", Map.of(), "api", false);
        assertThat(old.execute(withdrawnRequest).status()).isEqualTo("WAITING_USER_APPROVAL");
        when(registry.getByAgentId("legacy-selected-agent")).thenReturn(Optional.empty());
        assertThat(fresh.resume(new ApprovalCallbackRequest("approval-" + withdrawn, withdrawn, "session", ApprovalStatus.APPROVED, "1", null, run, 1)).status())
                .isEqualTo("FAILED");
        assertThat(effects).hasValue(1);
        System.out.println("Legacy recovery run=" + run + ", completed original effects=1, withdrawn target effects=0");
    }
}
