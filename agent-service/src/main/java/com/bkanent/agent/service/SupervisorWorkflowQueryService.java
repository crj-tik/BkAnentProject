package com.bkanent.agent.service;

import com.bkanent.agent.memory.MemoryStoreClient;
import com.bkanent.agent.model.distributed.SupervisorWorkflowView;
import com.bkanent.agent.model.distributed.TaskArtifactView;
import com.bkanent.agent.workflow.GraphCheckpointStore;
import com.bkanent.agent.workflow.SupervisorWorkflowState;
import com.bkanent.common.agent.ArtifactQueryResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class SupervisorWorkflowQueryService {

    private final GraphCheckpointStore checkpointStore;
    private final MemoryStoreClient memoryStoreClient;
    private final AgentPermissionService agentPermissionService;

    public SupervisorWorkflowQueryService(GraphCheckpointStore checkpointStore,
                                          MemoryStoreClient memoryStoreClient,
                                          AgentPermissionService agentPermissionService) {
        this.checkpointStore = checkpointStore;
        this.memoryStoreClient = memoryStoreClient;
        this.agentPermissionService = agentPermissionService;
    }

    public Optional<SupervisorWorkflowView> findWorkflow(String taskId) {
        return checkpointStore.load(taskId).map(this::toView);
    }

    public Optional<SupervisorWorkflowView> findWorkflow(String taskId, String userId) {
        return checkpointStore.load(taskId)
                .map(state -> {
                    agentPermissionService.assertCanReadWorkflow(userId, state.userId(), state.sessionId(), state.taskId(), state.traceId());
                    return toView(state);
                });
    }

    public List<TaskArtifactView> listArtifacts(String taskId, String userId) {
        agentPermissionService.assertCanReadTaskArtifacts(userId);
        return checkpointStore.load(taskId)
                .map(state -> {
                    agentPermissionService.assertCanReadWorkflow(userId, state.userId(), state.sessionId(), state.taskId(), state.traceId());
                    return state;
                })
                .map(state -> memoryStoreClient.listArtifactsByTask(taskId, state.sessionId()))
                .stream()
                .flatMap(List::stream)
                .map(this::toView)
                .toList();
    }

    private SupervisorWorkflowView toView(SupervisorWorkflowState state) {
        return new SupervisorWorkflowView(
                state.sessionId(),
                state.taskId(),
                state.traceId(),
                state.workflowStatus().name(),
                state.selectedAgentId(),
                state.handoffHistory(),
                state.artifactIds(),
                state.pendingApproval(),
                state.latestApprovalDecision(),
                state.latestAgentResponse(),
                state.finalAnswer()
        );
    }

    private TaskArtifactView toView(ArtifactQueryResponse response) {
        return new TaskArtifactView(
                response.meta().artifactId(),
                response.meta().taskId(),
                response.meta().sessionId(),
                response.meta().agentId(),
                response.meta().artifactType(),
                response.meta().version(),
                response.content() == null ? null : String.valueOf(response.content()),
                response.meta().metadata() == null ? null : String.valueOf(response.meta().metadata()),
                response.meta().createdAt()
        );
    }
}
