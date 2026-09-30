package com.bkanent.agent.service;

import com.bkanent.agent.memory.MemoryStoreClient;
import com.bkanent.agent.model.distributed.SupervisorWorkflowView;
import com.bkanent.agent.model.distributed.TaskArtifactView;
import com.bkanent.agent.workflow.GraphCheckpointStore;
import com.bkanent.agent.workflow.SupervisorWorkflowState;
import com.bkanent.common.agent.ArtifactQueryResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class SupervisorWorkflowQueryService {

    private final GraphCheckpointStore checkpointStore;
    private final MemoryStoreClient memoryStoreClient;
    private final AgentPermissionService agentPermissionService;
    private final ObjectMapper objectMapper;

    public SupervisorWorkflowQueryService(GraphCheckpointStore checkpointStore,
                                          MemoryStoreClient memoryStoreClient,
                                          AgentPermissionService agentPermissionService,
                                          ObjectMapper objectMapper) {
        this.checkpointStore = checkpointStore;
        this.memoryStoreClient = memoryStoreClient;
        this.agentPermissionService = agentPermissionService;
        this.objectMapper = objectMapper;
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
                writeJson(response.content()),
                writeJson(response.meta().metadata()),
                response.meta().createdAt()
        );
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize task artifact", exception);
        }
    }
}
