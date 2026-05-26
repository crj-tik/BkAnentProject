package com.bkanent.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.bkanent.agent.entity.AgentAsyncWorkflowEntity;
import com.bkanent.agent.mapper.AgentAsyncWorkflowMapper;
import com.bkanent.agent.model.distributed.SupervisorAsyncWorkflowCreateResponse;
import com.bkanent.agent.model.distributed.SupervisorAsyncWorkflowStatusResponse;
import com.bkanent.agent.model.distributed.SupervisorAsyncWorkflowView;
import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.agent.model.distributed.SupervisorTaskResponse;
import com.bkanent.agent.stream.SessionStreamService;
import com.bkanent.common.agent.SessionStreamEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class SupervisorAsyncWorkflowService {

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "supervisor-async-workflow");
        thread.setDaemon(true);
        return thread;
    });

    private final SupervisorWorkflowService supervisorWorkflowService;
    private final SessionStreamService sessionStreamService;
    private final AgentMetricsService agentMetricsService;
    private final SupervisorGovernanceService supervisorGovernanceService;
    private final AgentPermissionService agentPermissionService;
    private final AgentAsyncWorkflowMapper agentAsyncWorkflowMapper;
    private final ObjectMapper objectMapper;

    public SupervisorAsyncWorkflowService(SupervisorWorkflowService supervisorWorkflowService,
                                          SessionStreamService sessionStreamService,
                                          AgentMetricsService agentMetricsService,
                                          SupervisorGovernanceService supervisorGovernanceService,
                                          AgentPermissionService agentPermissionService,
                                          AgentAsyncWorkflowMapper agentAsyncWorkflowMapper,
                                          ObjectMapper objectMapper) {
        this.supervisorWorkflowService = supervisorWorkflowService;
        this.sessionStreamService = sessionStreamService;
        this.agentMetricsService = agentMetricsService;
        this.supervisorGovernanceService = supervisorGovernanceService;
        this.agentPermissionService = agentPermissionService;
        this.agentAsyncWorkflowMapper = agentAsyncWorkflowMapper;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void reconcilePendingWorkflows() {
        agentAsyncWorkflowMapper.update(
                null,
                new LambdaUpdateWrapper<AgentAsyncWorkflowEntity>()
                        .in(AgentAsyncWorkflowEntity::getStatus, "ACCEPTED", "RUNNING")
                        .set(AgentAsyncWorkflowEntity::getStatus, "FAILED")
                        .set(AgentAsyncWorkflowEntity::getErrorMessage, "supervisor restarted before async workflow completed")
                        .set(AgentAsyncWorkflowEntity::getFinishedAtMs, System.currentTimeMillis())
        );
    }

    public SupervisorAsyncWorkflowCreateResponse submitWorkflow(SupervisorTaskRequest request) {
        String message = request.userMessage() == null ? "" : request.userMessage().trim();
        if (message.isBlank()) {
            throw new IllegalArgumentException("userMessage must not be blank");
        }

        String sessionId = StringUtils.hasText(request.sessionId()) ? request.sessionId() : UUID.randomUUID().toString();
        String taskId = StringUtils.hasText(request.requestId()) ? request.requestId() : UUID.randomUUID().toString();
        String traceId = StringUtils.hasText(request.traceId()) ? request.traceId() : taskId;
        String asyncWorkflowId = UUID.randomUUID().toString();
        SupervisorTaskRequest normalizedRequest = new SupervisorTaskRequest(
                sessionId,
                request.userId(),
                taskId,
                traceId,
                request.userMessage(),
                request.context(),
                request.channel(),
                request.stream()
        );

        AgentAsyncWorkflowEntity entity = new AgentAsyncWorkflowEntity();
        entity.setAsyncWorkflowId(asyncWorkflowId);
        entity.setSessionId(sessionId);
        entity.setTaskId(taskId);
        entity.setTraceId(traceId);
        entity.setUserId(normalizedRequest.userId());
        entity.setStatus("ACCEPTED");
        entity.setOriginalRequestJson(writeJson(normalizedRequest));
        agentAsyncWorkflowMapper.insert(entity);
        publish(sessionId, taskId, "supervisor-agent", "supervisor.workflow_async.accepted", withGovernanceMetadata(Map.of(
                "asyncWorkflowId", asyncWorkflowId,
                "status", entity.getStatus(),
                "userId", request.userId() == null ? "" : request.userId(),
                "stage", "async_workflow",
                "durationMs", 0L
        ), normalizedRequest.context()), traceId);
        return toCreateResponse(entity);
    }

    public SupervisorAsyncWorkflowStatusResponse queryStatus(String asyncWorkflowId, String userId) {
        AgentAsyncWorkflowEntity entity = findEntity(asyncWorkflowId);
        if (entity == null) {
            return null;
        }
        agentPermissionService.assertCanManageAsyncResource(
                userId,
                entity.getUserId(),
                entity.getSessionId(),
                entity.getTaskId(),
                entity.getTraceId(),
                "async workflow query"
        );
        return toStatusResponse(entity);
    }

    public SupervisorAsyncWorkflowView queryView(String asyncWorkflowId, String userId) {
        AgentAsyncWorkflowEntity entity = findEntity(asyncWorkflowId);
        if (entity == null) {
            return null;
        }
        agentPermissionService.assertCanManageAsyncResource(
                userId,
                entity.getUserId(),
                entity.getSessionId(),
                entity.getTaskId(),
                entity.getTraceId(),
                "async workflow view"
        );
        return new SupervisorAsyncWorkflowView(
                entity.getSessionId(),
                entity.getTaskId(),
                entity.getAsyncWorkflowId(),
                entity.getStatus(),
                entity.getErrorMessage(),
                entity.getTraceId()
        );
    }

    public SupervisorAsyncWorkflowStatusResponse cancelWorkflow(String asyncWorkflowId, String userId) {
        AgentAsyncWorkflowEntity entity = findEntity(asyncWorkflowId);
        if (entity == null) {
            return null;
        }
        agentPermissionService.assertCanManageAsyncResource(
                userId,
                entity.getUserId(),
                entity.getSessionId(),
                entity.getTaskId(),
                entity.getTraceId(),
                "async workflow cancel"
        );
        if (isTerminal(entity.getStatus())) {
            return toStatusResponse(entity);
        }
        entity.setCancelRequested(1);
        entity.setStatus("CANCELLED");
        entity.setErrorMessage("workflow cancelled by user");
        entity.setFinishedAtMs(System.currentTimeMillis());
        agentAsyncWorkflowMapper.updateById(entity);
        publish(entity.getSessionId(), entity.getTaskId(), "supervisor-agent", "supervisor.workflow_async.cancelled", Map.of(
                "asyncWorkflowId", entity.getAsyncWorkflowId(),
                "status", entity.getStatus(),
                "userId", entity.getUserId() == null ? "" : entity.getUserId(),
                "stage", "async_workflow",
                "durationMs", elapsed(entity)
        ), entity.getTraceId());
        agentMetricsService.recordAsyncWorkflow("CANCELLED", elapsed(entity));
        return toStatusResponse(entity);
    }

    public SupervisorAsyncWorkflowCreateResponse retryWorkflow(String asyncWorkflowId, String userId) {
        AgentAsyncWorkflowEntity entity = findEntity(asyncWorkflowId);
        if (entity == null) {
            return null;
        }
        agentPermissionService.assertCanManageAsyncResource(
                userId,
                entity.getUserId(),
                entity.getSessionId(),
                entity.getTaskId(),
                entity.getTraceId(),
                "async workflow retry"
        );
        if (!isTerminal(entity.getStatus())) {
            throw new IllegalStateException("async workflow is still running");
        }
        SupervisorTaskRequest originalRequest = readRequest(entity.getOriginalRequestJson());
        SupervisorTaskRequest replayRequest = new SupervisorTaskRequest(
                originalRequest.sessionId(),
                originalRequest.userId(),
                null,
                null,
                originalRequest.userMessage(),
                originalRequest.context(),
                originalRequest.channel(),
                originalRequest.stream()
        );
        publish(entity.getSessionId(), entity.getTaskId(), "supervisor-agent", "supervisor.workflow_async.retry_requested", Map.of(
                "asyncWorkflowId", entity.getAsyncWorkflowId(),
                "status", entity.getStatus(),
                "userId", entity.getUserId() == null ? "" : entity.getUserId(),
                "stage", "async_workflow",
                "durationMs", elapsed(entity)
        ), entity.getTraceId());
        return submitWorkflow(replayRequest);
    }

    public SseEmitter subscribeWorkflowStream(String asyncWorkflowId, String userId) {
        AgentAsyncWorkflowEntity entity = findEntity(asyncWorkflowId);
        if (entity == null) {
            SseEmitter emitter = new SseEmitter(0L);
            emitter.completeWithError(new IllegalArgumentException("async workflow not found"));
            return emitter;
        }
        agentPermissionService.assertCanManageAsyncResource(
                userId,
                entity.getUserId(),
                entity.getSessionId(),
                entity.getTaskId(),
                entity.getTraceId(),
                "async workflow stream subscribe"
        );
        return sessionStreamService.subscribe(entity.getSessionId());
    }

    public void dispatchPendingWorkflows(int batchSize) {
        java.util.List<AgentAsyncWorkflowEntity> pending = agentAsyncWorkflowMapper.selectList(
                new LambdaQueryWrapper<AgentAsyncWorkflowEntity>()
                        .eq(AgentAsyncWorkflowEntity::getStatus, "ACCEPTED")
                        .orderByAsc(AgentAsyncWorkflowEntity::getCreatedAt)
                        .last("limit " + Math.max(1, batchSize))
        );
        for (AgentAsyncWorkflowEntity pendingEntity : pending) {
            if (!claimWorkflow(pendingEntity.getId())) {
                continue;
            }
            EXECUTOR.execute(() -> runClaimedWorkflow(pendingEntity.getAsyncWorkflowId()));
        }
    }

    private void runClaimedWorkflow(String asyncWorkflowId) {
        AgentAsyncWorkflowEntity entity = findEntity(asyncWorkflowId);
        if (entity == null || Integer.valueOf(1).equals(entity.getCancelRequested())) {
            return;
        }
        if (!"RUNNING".equalsIgnoreCase(entity.getStatus())) {
            return;
        }
        SupervisorTaskRequest request = readRequest(entity.getOriginalRequestJson());
        publish(entity.getSessionId(), entity.getTaskId(), "supervisor-agent", "supervisor.workflow_async.status", Map.of(
                "asyncWorkflowId", entity.getAsyncWorkflowId(),
                "status", entity.getStatus(),
                "userId", entity.getUserId() == null ? "" : entity.getUserId(),
                "stage", "async_workflow",
                "durationMs", elapsed(entity)
        ), entity.getTraceId());
        try {
            SupervisorTaskResponse result = supervisorWorkflowService.startWorkflow(request);
            entity = findEntity(asyncWorkflowId);
            if (entity == null || Integer.valueOf(1).equals(entity.getCancelRequested())) {
                return;
            }
            entity.setResultJson(writeJson(result));
            entity.setStatus(result.status());
            entity.setFinishedAtMs(System.currentTimeMillis());
            entity.setErrorMessage(null);
            agentAsyncWorkflowMapper.updateById(entity);
            publish(entity.getSessionId(), entity.getTaskId(), "supervisor-agent", "supervisor.workflow_async.completed", Map.of(
                    "asyncWorkflowId", entity.getAsyncWorkflowId(),
                    "status", entity.getStatus(),
                    "userId", entity.getUserId() == null ? "" : entity.getUserId(),
                    "stage", "async_workflow",
                    "durationMs", elapsed(entity)
            ), entity.getTraceId());
            agentMetricsService.recordAsyncWorkflow(entity.getStatus(), elapsed(entity));
        } catch (Exception exception) {
            entity = findEntity(asyncWorkflowId);
            if (entity == null || Integer.valueOf(1).equals(entity.getCancelRequested())) {
                return;
            }
            entity.setStatus("FAILED");
            entity.setErrorMessage(exception.getMessage());
            entity.setFinishedAtMs(System.currentTimeMillis());
            agentAsyncWorkflowMapper.updateById(entity);
            publish(entity.getSessionId(), entity.getTaskId(), "supervisor-agent", "supervisor.workflow_async.failed", Map.of(
                    "asyncWorkflowId", entity.getAsyncWorkflowId(),
                    "status", entity.getStatus(),
                    "errorMessage", entity.getErrorMessage() == null ? "" : entity.getErrorMessage(),
                    "userId", entity.getUserId() == null ? "" : entity.getUserId(),
                    "stage", "async_workflow",
                    "durationMs", elapsed(entity)
            ), entity.getTraceId());
            agentMetricsService.recordAsyncWorkflow("FAILED", elapsed(entity));
        }
    }

    private long elapsed(AgentAsyncWorkflowEntity entity) {
        return entity.getStartedAtMs() == null ? 0L : Math.max(0L, System.currentTimeMillis() - entity.getStartedAtMs());
    }

    private void publish(String sessionId,
                         String taskId,
                         String agentId,
                         String eventType,
                         Map<String, Object> metadata,
                         String traceId) {
        sessionStreamService.publish(new SessionStreamEvent(
                sessionId,
                taskId,
                agentId,
                eventType,
                eventType,
                metadata,
                traceId,
                System.currentTimeMillis()
        ));
    }

    private Map<String, Object> withGovernanceMetadata(Map<String, Object> metadata, Map<String, Object> context) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>(metadata);
        merged.putAll(supervisorGovernanceService.extractGovernanceMetadata(context));
        return Map.copyOf(merged);
    }

    private AgentAsyncWorkflowEntity findEntity(String asyncWorkflowId) {
        return agentAsyncWorkflowMapper.selectOne(
                new LambdaQueryWrapper<AgentAsyncWorkflowEntity>()
                        .eq(AgentAsyncWorkflowEntity::getAsyncWorkflowId, asyncWorkflowId)
                        .last("limit 1")
        );
    }

    private boolean claimWorkflow(Long id) {
        return agentAsyncWorkflowMapper.update(
                null,
                new LambdaUpdateWrapper<AgentAsyncWorkflowEntity>()
                        .eq(AgentAsyncWorkflowEntity::getId, id)
                        .eq(AgentAsyncWorkflowEntity::getStatus, "ACCEPTED")
                        .set(AgentAsyncWorkflowEntity::getStatus, "RUNNING")
                        .set(AgentAsyncWorkflowEntity::getStartedAtMs, System.currentTimeMillis())
        ) > 0;
    }

    private SupervisorAsyncWorkflowCreateResponse toCreateResponse(AgentAsyncWorkflowEntity entity) {
        return new SupervisorAsyncWorkflowCreateResponse(
                entity.getSessionId(),
                entity.getTaskId(),
                entity.getAsyncWorkflowId(),
                entity.getStatus(),
                entity.getTraceId()
        );
    }

    private SupervisorAsyncWorkflowStatusResponse toStatusResponse(AgentAsyncWorkflowEntity entity) {
        return new SupervisorAsyncWorkflowStatusResponse(
                entity.getSessionId(),
                entity.getTaskId(),
                entity.getAsyncWorkflowId(),
                entity.getStatus(),
                readResponse(entity.getResultJson()),
                entity.getErrorMessage(),
                entity.getTraceId()
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize async workflow payload", exception);
        }
    }

    private SupervisorTaskRequest readRequest(String json) {
        try {
            return objectMapper.readValue(json, SupervisorTaskRequest.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to deserialize async workflow request", exception);
        }
    }

    private SupervisorTaskResponse readResponse(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, SupervisorTaskResponse.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to deserialize async workflow response", exception);
        }
    }

    private boolean isTerminal(String status) {
        return "COMPLETED".equalsIgnoreCase(status)
                || "FAILED".equalsIgnoreCase(status)
                || "CANCELLED".equalsIgnoreCase(status);
    }
}
