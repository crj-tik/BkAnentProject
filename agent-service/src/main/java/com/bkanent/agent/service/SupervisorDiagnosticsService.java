package com.bkanent.agent.service;

import com.bkanent.agent.memory.MemoryStoreClient;
import com.bkanent.agent.model.distributed.SupervisorAsyncTaskView;
import com.bkanent.agent.model.distributed.SupervisorAsyncWorkflowView;
import com.bkanent.agent.model.distributed.SessionEventAuditView;
import com.bkanent.agent.model.distributed.SupervisorDiagnosticsView;
import com.bkanent.agent.model.distributed.SupervisorWorkflowView;
import com.bkanent.agent.model.distributed.TaskArtifactView;
import com.bkanent.agent.workflow.GraphCheckpointStore;
import com.bkanent.agent.workflow.SupervisorWorkflowState;
import com.bkanent.common.agent.ArtifactQueryResponse;
import com.bkanent.common.agent.HandoffRelationQueryResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class SupervisorDiagnosticsService {

    private final GraphCheckpointStore checkpointStore;
    private final MemoryStoreClient memoryStoreClient;
    private final SupervisorWorkflowQueryService supervisorWorkflowQueryService;
    private final SupervisorAsyncTaskService supervisorAsyncTaskService;
    private final SupervisorAsyncWorkflowService supervisorAsyncWorkflowService;
    private final AgentPermissionService agentPermissionService;
    private final SupervisorEventAuditQueryService supervisorEventAuditQueryService;

    public SupervisorDiagnosticsService(GraphCheckpointStore checkpointStore,
                                        MemoryStoreClient memoryStoreClient,
                                        SupervisorWorkflowQueryService supervisorWorkflowQueryService,
                                        SupervisorAsyncTaskService supervisorAsyncTaskService,
                                        SupervisorAsyncWorkflowService supervisorAsyncWorkflowService,
                                        AgentPermissionService agentPermissionService,
                                        SupervisorEventAuditQueryService supervisorEventAuditQueryService) {
        this.checkpointStore = checkpointStore;
        this.memoryStoreClient = memoryStoreClient;
        this.supervisorWorkflowQueryService = supervisorWorkflowQueryService;
        this.supervisorAsyncTaskService = supervisorAsyncTaskService;
        this.supervisorAsyncWorkflowService = supervisorAsyncWorkflowService;
        this.agentPermissionService = agentPermissionService;
        this.supervisorEventAuditQueryService = supervisorEventAuditQueryService;
    }

    public SupervisorDiagnosticsView diagnose(String userId,
                                              String taskId,
                                              String traceId,
                                              String approvalId,
                                              String artifactId,
                                              String asyncTaskId,
                                              String asyncWorkflowId,
                                              String grayStrategyVersion) {
        if (taskId != null && !taskId.isBlank()) {
            SupervisorWorkflowView workflow = supervisorWorkflowQueryService.findWorkflow(taskId, userId).orElse(null);
            List<TaskArtifactView> artifacts = workflow == null ? List.of() : supervisorWorkflowQueryService.listArtifacts(taskId, userId);
            List<Object> handoffs = workflow == null ? List.of() : memoryStoreClient.listHandoffsByTask(taskId).stream().map(HandoffRelationQueryResponse::packet).map(Object.class::cast).toList();
            return new SupervisorDiagnosticsView("taskId", taskId, workflow, null, artifacts, handoffs, null, null);
        }
        if (traceId != null && !traceId.isBlank()) {
            return diagnoseWorkflow(userId, checkpointStore.findByTraceId(traceId), "traceId", traceId);
        }
        if (approvalId != null && !approvalId.isBlank()) {
            return diagnoseWorkflow(userId, checkpointStore.findByApprovalId(approvalId), "approvalId", approvalId);
        }
        if (artifactId != null && !artifactId.isBlank()) {
            ArtifactQueryResponse response = memoryStoreClient.getArtifactById(artifactId);
            agentPermissionService.assertCanReadTaskArtifacts(userId);
            agentPermissionService.assertCanReadWorkflow(
                    userId,
                    valueOrEmpty(response.meta().metadata(), "userId"),
                    response.meta().sessionId(),
                    response.meta().taskId(),
                    response.meta().traceId()
            );
            return new SupervisorDiagnosticsView(
                    "artifactId",
                    artifactId,
                    supervisorWorkflowQueryService.findWorkflow(response.meta().taskId(), userId).orElse(null),
                    toArtifactView(response),
                    List.of(),
                    List.of(),
                    null,
                    null
            );
        }
        if (asyncTaskId != null && !asyncTaskId.isBlank()) {
            SupervisorAsyncTaskView asyncTask = supervisorAsyncTaskService.queryView(asyncTaskId, userId);
            return new SupervisorDiagnosticsView("asyncTaskId", asyncTaskId, null, null, List.of(), List.of(), asyncTask, null);
        }
        if (asyncWorkflowId != null && !asyncWorkflowId.isBlank()) {
            SupervisorAsyncWorkflowView asyncWorkflow = supervisorAsyncWorkflowService.queryView(asyncWorkflowId, userId);
            return new SupervisorDiagnosticsView("asyncWorkflowId", asyncWorkflowId, null, null, List.of(), List.of(), null, asyncWorkflow);
        }
        if (grayStrategyVersion != null && !grayStrategyVersion.isBlank()) {
            List<SessionEventAuditView> events = supervisorEventAuditQueryService.query(
                    null, null, null, null, null, grayStrategyVersion, null, true, 20
            );
            String matchedTaskId = events.stream()
                    .map(SessionEventAuditView::taskId)
                    .filter(value -> value != null && !value.isBlank())
                    .findFirst()
                    .orElse(null);
            if (matchedTaskId == null) {
                return new SupervisorDiagnosticsView("grayStrategyVersion", grayStrategyVersion, null, null, List.of(), List.of(), null, null);
            }
            SupervisorWorkflowView workflow = supervisorWorkflowQueryService.findWorkflow(matchedTaskId, userId).orElse(null);
            List<TaskArtifactView> artifacts = workflow == null ? List.of() : supervisorWorkflowQueryService.listArtifacts(matchedTaskId, userId);
            List<Object> handoffs = workflow == null ? List.of() : memoryStoreClient.listHandoffsByTask(matchedTaskId).stream().map(HandoffRelationQueryResponse::packet).map(Object.class::cast).toList();
            return new SupervisorDiagnosticsView("grayStrategyVersion", grayStrategyVersion, workflow, null, artifacts, handoffs, null, null);
        }
        throw new IllegalArgumentException("At least one diagnostics key is required");
    }

    private SupervisorDiagnosticsView diagnoseWorkflow(String userId,
                                                       Optional<SupervisorWorkflowState> stateOptional,
                                                       String lookupType,
                                                       String lookupValue) {
        if (stateOptional.isEmpty()) {
            return new SupervisorDiagnosticsView(lookupType, lookupValue, null, null, List.of(), List.of(), null, null);
        }
        SupervisorWorkflowState state = stateOptional.get();
        agentPermissionService.assertCanReadWorkflow(userId, state.userId(), state.sessionId(), state.taskId(), state.traceId());
        SupervisorWorkflowView workflow = supervisorWorkflowQueryService.findWorkflow(state.taskId(), userId).orElse(null);
        List<TaskArtifactView> artifacts = supervisorWorkflowQueryService.listArtifacts(state.taskId(), userId);
        List<Object> handoffs = memoryStoreClient.listHandoffsByTask(state.taskId()).stream().map(HandoffRelationQueryResponse::packet).map(Object.class::cast).toList();
        return new SupervisorDiagnosticsView(lookupType, lookupValue, workflow, null, artifacts, handoffs, null, null);
    }

    private TaskArtifactView toArtifactView(ArtifactQueryResponse response) {
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

    private String valueOrEmpty(java.util.Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return "";
        }
        Object value = metadata.get(key);
        return value == null ? "" : String.valueOf(value);
    }
}
