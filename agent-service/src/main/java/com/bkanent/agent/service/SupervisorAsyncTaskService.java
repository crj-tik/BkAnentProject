package com.bkanent.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.bkanent.agent.client.A2aAgentClient;
import com.bkanent.agent.config.DistributedAgentProperties;
import com.bkanent.agent.entity.AgentAsyncTaskEntity;
import com.bkanent.agent.graph.SupervisorGraphPlanner;
import com.bkanent.agent.graph.SupervisorGraphState;
import com.bkanent.agent.graph.node.BuildInvokeRequestNode;
import com.bkanent.agent.mapper.AgentAsyncTaskMapper;
import com.bkanent.agent.model.distributed.SupervisorAsyncTaskCreateResponse;
import com.bkanent.agent.model.distributed.SupervisorAsyncTaskStatusResponse;
import com.bkanent.agent.model.distributed.SupervisorAsyncTaskView;
import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.agent.model.distributed.SupervisorTaskResponse;
import com.bkanent.agent.registry.AgentRegistry;
import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import com.bkanent.agent.stream.SessionStreamService;
import com.bkanent.common.agent.A2aAsyncTaskCreateResponse;
import com.bkanent.common.agent.A2aAsyncTaskStatusResponse;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.SessionStreamEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

@Service
public class SupervisorAsyncTaskService {
    private final AgentRegistry agentRegistry;
    private final A2aAgentClient a2aAgentClient;
    private final SupervisorTaskService supervisorTaskService;
    private final SupervisorGraphPlanner supervisorGraphPlanner;
    private final BuildInvokeRequestNode buildInvokeRequestNode;
    private final SessionStreamService sessionStreamService;
    private final AgentMetricsService agentMetricsService;
    private final SupervisorGovernanceService supervisorGovernanceService;
    private final AgentPermissionService agentPermissionService;
    private final AgentAsyncTaskMapper agentAsyncTaskMapper;
    private final ObjectMapper objectMapper;
    private final SupervisorAgentRoutingService supervisorAgentRoutingService;
    private final DistributedAgentProperties distributedAgentProperties;
    private final ThreadPoolTaskExecutor executor;

    public SupervisorAsyncTaskService(AgentRegistry agentRegistry,
                                      A2aAgentClient a2aAgentClient,
                                      SupervisorTaskService supervisorTaskService,
                                      SupervisorGraphPlanner supervisorGraphPlanner,
                                      BuildInvokeRequestNode buildInvokeRequestNode,
                                      SessionStreamService sessionStreamService,
                                      AgentMetricsService agentMetricsService,
                                      SupervisorGovernanceService supervisorGovernanceService,
                                      AgentPermissionService agentPermissionService,
                                      AgentAsyncTaskMapper agentAsyncTaskMapper,
                                      ObjectMapper objectMapper,
                                      SupervisorAgentRoutingService supervisorAgentRoutingService,
                                      DistributedAgentProperties distributedAgentProperties,
                                      ThreadPoolTaskExecutor supervisorAsyncExecutor) {
        this.agentRegistry = agentRegistry;
        this.a2aAgentClient = a2aAgentClient;
        this.supervisorTaskService = supervisorTaskService;
        this.supervisorGraphPlanner = supervisorGraphPlanner;
        this.buildInvokeRequestNode = buildInvokeRequestNode;
        this.sessionStreamService = sessionStreamService;
        this.agentMetricsService = agentMetricsService;
        this.supervisorGovernanceService = supervisorGovernanceService;
        this.agentPermissionService = agentPermissionService;
        this.agentAsyncTaskMapper = agentAsyncTaskMapper;
        this.objectMapper = objectMapper;
        this.supervisorAgentRoutingService = supervisorAgentRoutingService;
        this.distributedAgentProperties = distributedAgentProperties;
        this.executor = supervisorAsyncExecutor;
    }

    @PostConstruct
    public void reconcilePendingLocalTasks() {
        long now = System.currentTimeMillis();
        agentAsyncTaskMapper.update(
                null,
                new LambdaUpdateWrapper<AgentAsyncTaskEntity>()
                        .ne(AgentAsyncTaskEntity::getMode, "CHILD_AGENT")
                        .eq(AgentAsyncTaskEntity::getStatus, "RUNNING")
                        .and(wrapper -> wrapper.isNull(AgentAsyncTaskEntity::getLeaseUntilMs)
                                .or().le(AgentAsyncTaskEntity::getLeaseUntilMs, now))
                        .set(AgentAsyncTaskEntity::getStatus, "ACCEPTED")
                        .set(AgentAsyncTaskEntity::getErrorMessage, "RECOVERED_AFTER_WORKER_RESTART")
                        .set(AgentAsyncTaskEntity::getLeaseOwner, null)
                        .set(AgentAsyncTaskEntity::getLeaseUntilMs, null)
                        .set(AgentAsyncTaskEntity::getNextAttemptAtMs, now)
                        .set(AgentAsyncTaskEntity::getFinishedAtMs, null)
        );
    }

    public SupervisorAsyncTaskCreateResponse submitTask(SupervisorTaskRequest request) {
        String message = request.userMessage() == null ? "" : request.userMessage().trim();
        if (message.isBlank()) {
            throw new IllegalArgumentException("userMessage must not be blank");
        }

        String sessionId = StringUtils.hasText(request.sessionId()) ? request.sessionId() : UUID.randomUUID().toString();
        String taskId = StringUtils.hasText(request.requestId()) ? request.requestId() : UUID.randomUUID().toString();
        String traceId = StringUtils.hasText(request.traceId()) ? request.traceId() : taskId;
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
        SupervisorGraphState graphState = supervisorGraphPlanner.plan(normalizedRequest, sessionId, taskId, traceId);
        List<String> parallelDomains = graphState.parallelDomains();
        if (parallelDomains.size() > 1 || Boolean.TRUE.equals(graphState.requireApproval())) {
            return submitLocal(normalizedRequest, sessionId, taskId, traceId, graphState.selectedAgentId(), "LOCAL_WORKFLOW");
        }

        RegisteredAgentDescriptor descriptor = agentRegistry.getByAgentId(graphState.selectedAgentId())
                .orElseGet(() -> selectAgent(graphState.domain(), message, normalizedRequest.context()));
        if (descriptor.agentCard() == null || !Boolean.TRUE.equals(descriptor.agentCard().supportsAsyncTask())) {
            return submitLocal(normalizedRequest, sessionId, taskId, traceId, descriptor.agentId(), "LOCAL_WORKFLOW");
        }

        AgentTaskInvokeRequest invokeRequest = buildInvokeRequestNode.build(
                normalizedRequest,
                graphState.withSelectedAgent(descriptor.agentId()),
                descriptor.agentId(),
                null,
                0
        );
        A2aAsyncTaskCreateResponse childTask = a2aAgentClient.submitAsync(descriptor, invokeRequest);
        String asyncTaskId = UUID.randomUUID().toString();
        AgentAsyncTaskEntity entity = new AgentAsyncTaskEntity();
        entity.setAsyncTaskId(asyncTaskId);
        entity.setSessionId(sessionId);
        entity.setTaskId(taskId);
        entity.setTraceId(traceId);
        entity.setUserId(normalizedRequest.userId());
        entity.setSelectedAgentId(descriptor.agentId());
        entity.setMode("CHILD_AGENT");
        entity.setChildAsyncTaskId(childTask.asyncTaskId());
        entity.setStatus(childTask.status());
        entity.setOriginalRequestJson(writeJson(normalizedRequest));
        entity.setStartedAtMs(System.currentTimeMillis());
        agentAsyncTaskMapper.insert(entity);
        publish(sessionId, taskId, descriptor.agentId(), "supervisor.async.accepted", withGovernanceMetadata(Map.of(
                "asyncTaskId", asyncTaskId,
                "childAsyncTaskId", childTask.asyncTaskId(),
                "mode", "CHILD_AGENT",
                "status", childTask.status(),
                "userId", request.userId() == null ? "" : request.userId(),
                "stage", "async_task",
                "durationMs", 0L
        ), normalizedRequest.context()), traceId);
        return toCreateResponse(entity);
    }

    public SupervisorAsyncTaskStatusResponse queryStatus(String asyncTaskId, String userId) {
        AgentAsyncTaskEntity entity = findEntity(asyncTaskId);
        if (entity == null) {
            return null;
        }
        agentPermissionService.assertCanManageAsyncResource(
                userId,
                entity.getUserId(),
                entity.getSessionId(),
                entity.getTaskId(),
                entity.getTraceId(),
                "async task query"
        );
        if ("CHILD_AGENT".equals(entity.getMode())) {
            RegisteredAgentDescriptor descriptor = agentRegistry.getByAgentId(entity.getSelectedAgentId()).orElse(null);
            if (descriptor == null) {
                entity.setStatus("FAILED");
                entity.setErrorMessage("registered agent not found");
                entity.setFinishedAtMs(System.currentTimeMillis());
                agentAsyncTaskMapper.updateById(entity);
                publish(entity.getSessionId(), entity.getTaskId(), entity.getSelectedAgentId(), "supervisor.async.failed", Map.of(
                        "asyncTaskId", asyncTaskId,
                        "mode", entity.getMode(),
                        "errorMessage", entity.getErrorMessage(),
                        "userId", entity.getUserId() == null ? "" : entity.getUserId(),
                        "stage", "async_task",
                        "durationMs", elapsed(entity)
                ), entity.getTraceId());
                agentMetricsService.recordAsyncTask(entity.getMode(), "FAILED", elapsed(entity));
                return toStatusResponse(entity);
            }
            A2aAsyncTaskStatusResponse childStatus = a2aAgentClient.queryAsyncStatus(descriptor, entity.getChildAsyncTaskId());
            String previousStatus = entity.getStatus();
            entity.setStatus(childStatus.status());
            entity.setErrorMessage(childStatus.errorMessage());
            if (childStatus.result() != null) {
                entity.setResultJson(writeJson(mapResponse(childStatus.result(), entity.getSelectedAgentId())));
            }
            if (isTerminal(entity.getStatus())) {
                entity.setFinishedAtMs(System.currentTimeMillis());
            }
            agentAsyncTaskMapper.updateById(entity);
            if (!Objects.equals(previousStatus, entity.getStatus())) {
                publish(entity.getSessionId(), entity.getTaskId(), entity.getSelectedAgentId(), "supervisor.async.status", Map.of(
                        "asyncTaskId", asyncTaskId,
                        "childAsyncTaskId", entity.getChildAsyncTaskId(),
                        "mode", entity.getMode(),
                        "status", entity.getStatus(),
                        "userId", entity.getUserId() == null ? "" : entity.getUserId(),
                        "stage", "async_task",
                        "durationMs", elapsed(entity)
                ), entity.getTraceId());
                if (isTerminal(entity.getStatus())) {
                    agentMetricsService.recordAsyncTask(entity.getMode(), entity.getStatus(), elapsed(entity));
                }
            }
        }
        return toStatusResponse(entity);
    }

    public SupervisorAsyncTaskView queryView(String asyncTaskId, String userId) {
        AgentAsyncTaskEntity entity = findEntity(asyncTaskId);
        if (entity == null) {
            return null;
        }
        agentPermissionService.assertCanManageAsyncResource(
                userId,
                entity.getUserId(),
                entity.getSessionId(),
                entity.getTaskId(),
                entity.getTraceId(),
                "async task view"
        );
        return new SupervisorAsyncTaskView(
                entity.getSessionId(),
                entity.getTaskId(),
                entity.getAsyncTaskId(),
                entity.getStatus(),
                entity.getErrorMessage(),
                entity.getTraceId(),
                entity.getSelectedAgentId(),
                entity.getMode()
        );
    }

    private SupervisorAsyncTaskCreateResponse submitLocal(SupervisorTaskRequest request,
                                                          String sessionId,
                                                          String taskId,
                                                          String traceId,
                                                          String selectedAgentId,
                                                          String mode) {
        String asyncTaskId = UUID.randomUUID().toString();
        AgentAsyncTaskEntity entity = new AgentAsyncTaskEntity();
        entity.setAsyncTaskId(asyncTaskId);
        entity.setSessionId(sessionId);
        entity.setTaskId(taskId);
        entity.setTraceId(traceId);
        entity.setUserId(request.userId());
        entity.setSelectedAgentId(selectedAgentId);
        entity.setMode(mode);
        entity.setStatus("ACCEPTED");
        entity.setOriginalRequestJson(writeJson(request));
        entity.setAttemptCount(0);
        entity.setNextAttemptAtMs(System.currentTimeMillis());
        agentAsyncTaskMapper.insert(entity);
        publish(sessionId, taskId, selectedAgentId, "supervisor.async.accepted", withGovernanceMetadata(Map.of(
                "asyncTaskId", asyncTaskId,
                "mode", mode,
                "status", entity.getStatus(),
                "userId", request.userId() == null ? "" : request.userId(),
                "stage", "async_task",
                "durationMs", 0L
        ), request.context()), traceId);
        return toCreateResponse(entity);
    }

    public void dispatchPendingLocalTasks(int batchSize) {
        List<AgentAsyncTaskEntity> pending = agentAsyncTaskMapper.selectList(
                new LambdaQueryWrapper<AgentAsyncTaskEntity>()
                        .ne(AgentAsyncTaskEntity::getMode, "CHILD_AGENT")
                        .eq(AgentAsyncTaskEntity::getStatus, "ACCEPTED")
                        .and(wrapper -> wrapper.isNull(AgentAsyncTaskEntity::getNextAttemptAtMs)
                                .or().le(AgentAsyncTaskEntity::getNextAttemptAtMs, System.currentTimeMillis()))
                        .orderByAsc(AgentAsyncTaskEntity::getCreatedAt)
                        .last("limit " + Math.max(1, batchSize))
        );
        for (AgentAsyncTaskEntity pendingEntity : pending) {
            if (!claimTask(pendingEntity.getId())) {
                continue;
            }
            try {
                executor.execute(() -> runClaimedLocal(pendingEntity.getAsyncTaskId()));
            } catch (RejectedExecutionException exception) {
                releaseClaim(pendingEntity.getId());
            }
        }
    }

    private void runClaimedLocal(String asyncTaskId) {
        AgentAsyncTaskEntity entity = findEntity(asyncTaskId);
        if (entity == null || !"RUNNING".equalsIgnoreCase(entity.getStatus())) {
            return;
        }
        publish(entity.getSessionId(), entity.getTaskId(), entity.getSelectedAgentId(), "supervisor.async.status", Map.of(
                "asyncTaskId", entity.getAsyncTaskId(),
                "mode", entity.getMode(),
                "status", entity.getStatus(),
                "userId", entity.getUserId() == null ? "" : entity.getUserId(),
                "stage", "async_task",
                "durationMs", elapsed(entity)
        ), entity.getTraceId());
        try {
            SupervisorTaskRequest request = readRequest(entity.getOriginalRequestJson());
            SupervisorTaskResponse result = supervisorTaskService.submitTask(request);
            entity.setResultJson(writeJson(result));
            entity.setStatus(result.status());
            entity.setFinishedAtMs(System.currentTimeMillis());
            entity.setErrorMessage(null);
            entity.setLeaseOwner(null);
            entity.setLeaseUntilMs(null);
            if (!completeTask(entity)) {
                return;
            }
            publish(entity.getSessionId(), entity.getTaskId(), entity.getSelectedAgentId(), "supervisor.async.completed", Map.of(
                    "asyncTaskId", entity.getAsyncTaskId(),
                    "mode", entity.getMode(),
                    "status", entity.getStatus(),
                    "userId", entity.getUserId() == null ? "" : entity.getUserId(),
                    "stage", "async_task",
                    "durationMs", elapsed(entity)
            ), entity.getTraceId());
            agentMetricsService.recordAsyncTask(entity.getMode(), entity.getStatus(), elapsed(entity));
        } catch (Exception exception) {
            if (!retryOrFail(entity, exception)) {
                return;
            }
            boolean retry = "ACCEPTED".equalsIgnoreCase(entity.getStatus());
            String eventType = retry ? "supervisor.async.retry_scheduled" : "supervisor.async.failed";
            publish(entity.getSessionId(), entity.getTaskId(), entity.getSelectedAgentId(), eventType, Map.of(
                    "asyncTaskId", entity.getAsyncTaskId(),
                    "mode", entity.getMode(),
                    "status", entity.getStatus(),
                    "errorMessage", entity.getErrorMessage(),
                    "userId", entity.getUserId() == null ? "" : entity.getUserId(),
                    "stage", "async_task",
                    "durationMs", elapsed(entity)
            ), entity.getTraceId());
            if (!retry) {
                agentMetricsService.recordAsyncTask(entity.getMode(), "FAILED", elapsed(entity));
            }
        }
    }

    private boolean completeTask(AgentAsyncTaskEntity entity) {
        String workerId = distributedAgentProperties.getAsyncRuntime().getWorkerId();
        return agentAsyncTaskMapper.update(
                null,
                new LambdaUpdateWrapper<AgentAsyncTaskEntity>()
                        .eq(AgentAsyncTaskEntity::getId, entity.getId())
                        .eq(AgentAsyncTaskEntity::getStatus, "RUNNING")
                        .eq(AgentAsyncTaskEntity::getLeaseOwner, workerId)
                        .set(AgentAsyncTaskEntity::getStatus, entity.getStatus())
                        .set(AgentAsyncTaskEntity::getResultJson, entity.getResultJson())
                        .set(AgentAsyncTaskEntity::getErrorMessage, null)
                        .set(AgentAsyncTaskEntity::getFinishedAtMs, entity.getFinishedAtMs())
                        .set(AgentAsyncTaskEntity::getLeaseUntilMs, null)
        ) > 0;
    }

    private boolean retryOrFail(AgentAsyncTaskEntity entity, Exception exception) {
        DistributedAgentProperties.AsyncRuntimeProperties properties = distributedAgentProperties.getAsyncRuntime();
        int attempts = entity.getAttemptCount() == null ? 1 : entity.getAttemptCount();
        boolean retry = AsyncRuntimePolicy.shouldRetry(attempts, properties.getMaxAttempts(), exception);
        long now = System.currentTimeMillis();
        entity.setStatus(retry ? "ACCEPTED" : "FAILED");
        entity.setErrorMessage(AsyncRuntimePolicy.failureCode(exception));
        entity.setFinishedAtMs(retry ? null : now);
        entity.setLeaseOwner(null);
        entity.setLeaseUntilMs(null);
        entity.setNextAttemptAtMs(retry ? now + Math.max(0L, properties.getRetryDelaySeconds()) * 1000L : null);
        LambdaUpdateWrapper<AgentAsyncTaskEntity> update = new LambdaUpdateWrapper<AgentAsyncTaskEntity>()
                .eq(AgentAsyncTaskEntity::getId, entity.getId())
                .eq(AgentAsyncTaskEntity::getStatus, "RUNNING")
                .eq(AgentAsyncTaskEntity::getLeaseOwner, properties.getWorkerId())
                .set(AgentAsyncTaskEntity::getStatus, entity.getStatus())
                .set(AgentAsyncTaskEntity::getErrorMessage, entity.getErrorMessage())
                .set(AgentAsyncTaskEntity::getFinishedAtMs, entity.getFinishedAtMs())
                .set(AgentAsyncTaskEntity::getLeaseOwner, null)
                .set(AgentAsyncTaskEntity::getLeaseUntilMs, null)
                .set(AgentAsyncTaskEntity::getNextAttemptAtMs, entity.getNextAttemptAtMs());
        return agentAsyncTaskMapper.update(null, update) > 0;
    }

    private long elapsed(AgentAsyncTaskEntity entity) {
        return entity.getStartedAtMs() == null ? 0L : Math.max(0L, System.currentTimeMillis() - entity.getStartedAtMs());
    }

    private SupervisorTaskResponse mapResponse(com.bkanent.common.agent.AgentTaskInvokeResponse response, String selectedAgentId) {
        return new SupervisorTaskResponse(
                response.sessionId(),
                response.taskId(),
                response.status(),
                response.summary(),
                response.artifactIds(),
                response.traceId(),
                selectedAgentId,
                Map.of()
        );
    }

    private RegisteredAgentDescriptor selectAgent(String domain, String message, Map<String, Object> context) {
        RegisteredAgentDescriptor preferred = supervisorAgentRoutingService.selectAgent(domain, message, context);
        if (preferred != null) {
            return preferred;
        }
        return agentRegistry.getByAgentId("listing-agent")
                .orElseThrow(() -> new IllegalStateException("No registered agent available"));
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

    private AgentAsyncTaskEntity findEntity(String asyncTaskId) {
        return agentAsyncTaskMapper.selectOne(
                new LambdaQueryWrapper<AgentAsyncTaskEntity>()
                        .eq(AgentAsyncTaskEntity::getAsyncTaskId, asyncTaskId)
                        .last("limit 1")
        );
    }

    private boolean claimTask(Long id) {
        long now = System.currentTimeMillis();
        DistributedAgentProperties.AsyncRuntimeProperties properties = distributedAgentProperties.getAsyncRuntime();
        return agentAsyncTaskMapper.update(
                null,
                new LambdaUpdateWrapper<AgentAsyncTaskEntity>()
                        .eq(AgentAsyncTaskEntity::getId, id)
                        .eq(AgentAsyncTaskEntity::getStatus, "ACCEPTED")
                        .and(wrapper -> wrapper.isNull(AgentAsyncTaskEntity::getNextAttemptAtMs)
                                .or().le(AgentAsyncTaskEntity::getNextAttemptAtMs, now))
                        .set(AgentAsyncTaskEntity::getStatus, "RUNNING")
                        .set(AgentAsyncTaskEntity::getStartedAtMs, now)
                        .set(AgentAsyncTaskEntity::getLeaseOwner, properties.getWorkerId())
                        .set(AgentAsyncTaskEntity::getLeaseUntilMs,
                                now + Math.max(1L, properties.getLeaseTimeoutSeconds()) * 1000L)
                        .setSql("attempt_count = COALESCE(attempt_count, 0) + 1")
        ) > 0;
    }

    private void releaseClaim(Long id) {
        DistributedAgentProperties.AsyncRuntimeProperties properties = distributedAgentProperties.getAsyncRuntime();
        agentAsyncTaskMapper.update(
                null,
                new LambdaUpdateWrapper<AgentAsyncTaskEntity>()
                        .eq(AgentAsyncTaskEntity::getId, id)
                        .eq(AgentAsyncTaskEntity::getStatus, "RUNNING")
                        .eq(AgentAsyncTaskEntity::getLeaseOwner, properties.getWorkerId())
                        .set(AgentAsyncTaskEntity::getStatus, "ACCEPTED")
                        .set(AgentAsyncTaskEntity::getLeaseOwner, null)
                        .set(AgentAsyncTaskEntity::getLeaseUntilMs, null)
                        .set(AgentAsyncTaskEntity::getNextAttemptAtMs, System.currentTimeMillis())
                        .setSql("attempt_count = GREATEST(COALESCE(attempt_count, 0) - 1, 0)")
        );
    }

    private SupervisorAsyncTaskCreateResponse toCreateResponse(AgentAsyncTaskEntity entity) {
        return new SupervisorAsyncTaskCreateResponse(
                entity.getSessionId(),
                entity.getTaskId(),
                entity.getAsyncTaskId(),
                entity.getStatus(),
                entity.getTraceId(),
                entity.getSelectedAgentId(),
                entity.getMode()
        );
    }

    private SupervisorAsyncTaskStatusResponse toStatusResponse(AgentAsyncTaskEntity entity) {
        return new SupervisorAsyncTaskStatusResponse(
                entity.getSessionId(),
                entity.getTaskId(),
                entity.getAsyncTaskId(),
                entity.getStatus(),
                readResponse(entity.getResultJson()),
                entity.getErrorMessage(),
                entity.getTraceId(),
                entity.getSelectedAgentId(),
                entity.getMode()
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize async task payload", exception);
        }
    }

    private SupervisorTaskResponse readResponse(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, SupervisorTaskResponse.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to deserialize async task response", exception);
        }
    }

    private SupervisorTaskRequest readRequest(String json) {
        try {
            return objectMapper.readValue(json, SupervisorTaskRequest.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to deserialize async task request", exception);
        }
    }

    private boolean isTerminal(String status) {
        return "COMPLETED".equalsIgnoreCase(status)
                || "FAILED".equalsIgnoreCase(status)
                || "CANCELLED".equalsIgnoreCase(status);
    }
}
