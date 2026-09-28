package com.bkanent.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.bkanent.agent.client.A2aAgentClient;
import com.bkanent.agent.config.DistributedAgentProperties;
import com.bkanent.agent.entity.AgentAsyncTaskEntity;
import com.bkanent.agent.entity.AgentAsyncWorkflowEntity;
import com.bkanent.agent.mapper.AgentAsyncTaskMapper;
import com.bkanent.agent.mapper.AgentAsyncWorkflowMapper;
import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.agent.model.distributed.SupervisorTaskResponse;
import com.bkanent.agent.registry.AgentDescriptorSource;
import com.bkanent.agent.registry.AgentRegistry;
import com.bkanent.agent.registry.AgentRuntimeType;
import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import com.bkanent.agent.service.SupervisorWorkflowService;
import com.bkanent.agent.stream.SessionStreamService;
import com.bkanent.common.agent.AgentCard;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SupervisorAsyncTaskLeaseTest {

    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "supervisor-async-lease-test"),
                AgentAsyncTaskEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "supervisor-async-workflow-lease-test"),
                AgentAsyncWorkflowEntity.class);
    }

    @AfterEach
    void shutDownExecutor() {
        executor.shutdown();
    }

    @Test
    void renewsSupervisorLeaseWhileAnA2aSubAgentCallIsRunning() throws Exception {
        AgentAsyncTaskMapper taskMapper = mock(AgentAsyncTaskMapper.class);
        A2aAgentClient a2aAgentClient = mock(A2aAgentClient.class);
        SupervisorTaskService supervisorTaskService = mock(SupervisorTaskService.class);
        SessionStreamService streamService = mock(SessionStreamService.class);
        AgentMetricsService metricsService = mock(AgentMetricsService.class);
        SupervisorGovernanceService governanceService = mock(SupervisorGovernanceService.class);
        AgentPermissionService permissionService = mock(AgentPermissionService.class);
        DistributedAgentProperties properties = new DistributedAgentProperties();
        properties.getAsyncRuntime().setWorkerId("subagent-lease-test");
        properties.getAsyncRuntime().setLeaseTimeoutSeconds(1L);
        properties.getAsyncRuntime().setChildTaskTimeoutMs(10_000L);
        when(governanceService.extractGovernanceMetadata(anyMap())).thenReturn(Map.of());

        AtomicReference<String> currentLeaseOwner = new AtomicReference<>();
        AtomicInteger taskMapperUpdates = new AtomicInteger();
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            LambdaUpdateWrapper<AgentAsyncTaskEntity> update = invocation.getArgument(1);
            taskMapperUpdates.incrementAndGet();
            update.getParamNameValuePairs().values().stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(value -> value.startsWith("subagent-lease-test-"))
                    .findFirst()
                    .ifPresent(currentLeaseOwner::set);
            return 1;
        }).when(taskMapper).update(isNull(), any(LambdaUpdateWrapper.class));

        SupervisorTaskRequest parentRequest = new SupervisorTaskRequest(
                "session-1", "user-1", "task-1", "trace-1", "find listings", Map.of(), "web", false);
        ObjectMapper objectMapper = new ObjectMapper();
        AgentAsyncTaskEntity pending = new AgentAsyncTaskEntity();
        pending.setId(1L);
        pending.setAsyncTaskId("async-task-1");
        pending.setSessionId("session-1");
        pending.setTaskId("task-1");
        pending.setTraceId("trace-1");
        pending.setUserId("user-1");
        pending.setSelectedAgentId("listing-agent");
        pending.setMode("LOCAL_WORKFLOW");
        pending.setStatus("ACCEPTED");
        pending.setAttemptCount(0);
        pending.setOriginalRequestJson(objectMapper.writeValueAsString(parentRequest));
        when(taskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(pending));
        when(taskMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            AgentAsyncTaskEntity running = new AgentAsyncTaskEntity();
            running.setId(1L);
            running.setAsyncTaskId("async-task-1");
            running.setSessionId("session-1");
            running.setTaskId("task-1");
            running.setTraceId("trace-1");
            running.setUserId("user-1");
            running.setSelectedAgentId("listing-agent");
            running.setMode("LOCAL_WORKFLOW");
            running.setStatus("RUNNING");
            running.setAttemptCount(1);
            running.setLeaseOwner(currentLeaseOwner.get());
            running.setOriginalRequestJson(objectMapper.writeValueAsString(parentRequest));
            return running;
        });

        CountDownLatch subAgentCallStarted = new CountDownLatch(1);
        CountDownLatch allowSubAgentCallToFinish = new CountDownLatch(1);
        CountDownLatch parentExecutionFinished = new CountDownLatch(1);
        RegisteredAgentDescriptor descriptor = listingAgent();
        AgentTaskInvokeRequest childRequest = new AgentTaskInvokeRequest(
                "session-1", "task-1", null, "trace-1", "supervisor-agent", "listing-agent",
                "listing.search", "listing", "find listings", Map.of(), List.of(), List.of(),
                "text", "idempotency-1", false);
        AgentTaskInvokeResponse childResponse = new AgentTaskInvokeResponse(
                "session-1", "task-1", "listing-agent", "COMPLETED", Map.of("answer", "done"),
                List.of(), List.of(), "done", "trace-1");
        when(a2aAgentClient.invoke(eq(descriptor), eq(childRequest))).thenAnswer(invocation -> {
            subAgentCallStarted.countDown();
            if (!allowSubAgentCallToFinish.await(8, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release the sub-agent call");
            }
            return childResponse;
        });

        A2aExecutionService a2aExecutionService = new A2aExecutionService(
                a2aAgentClient, streamService, permissionService, properties);
        when(supervisorTaskService.submitTask(any())).thenAnswer(invocation -> {
            try {
                assertThat(a2aExecutionService.execute(descriptor, childRequest, "single_agent", Map.of()))
                        .isSameAs(childResponse);
                return new SupervisorTaskResponse("session-1", "task-1", "COMPLETED", "done",
                        List.of(), "trace-1", "listing-agent", Map.of());
            } finally {
                parentExecutionFinished.countDown();
            }
        });

        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.initialize();
        SupervisorAsyncTaskService asyncTaskService = new SupervisorAsyncTaskService(
                mock(AgentRegistry.class), a2aExecutionService, supervisorTaskService,
                streamService, metricsService, governanceService, permissionService,
                taskMapper, objectMapper, properties, executor);

        try {
            asyncTaskService.dispatchPendingLocalTasks(1);

            assertThat(subAgentCallStarted.await(4, TimeUnit.SECONDS)).isTrue();
            awaitAtLeastThreeMapperUpdates(taskMapperUpdates);
            assertThat(currentLeaseOwner.get()).startsWith("subagent-lease-test-");
            assertThat(taskMapperUpdates.get())
                    .as("lease recovery, claim, and at least one heartbeat while the A2A sub-agent is blocked")
                    .isGreaterThanOrEqualTo(3);

            allowSubAgentCallToFinish.countDown();
            assertThat(parentExecutionFinished.await(4, TimeUnit.SECONDS)).isTrue();
        } finally {
            allowSubAgentCallToFinish.countDown();
        }
    }

    @Test
    void renewsWorkflowLeaseWhileAnA2aSubAgentCallIsRunning() throws Exception {
        AgentAsyncWorkflowMapper workflowMapper = mock(AgentAsyncWorkflowMapper.class);
        A2aAgentClient a2aAgentClient = mock(A2aAgentClient.class);
        SupervisorWorkflowService supervisorWorkflowService = mock(SupervisorWorkflowService.class);
        SessionStreamService streamService = mock(SessionStreamService.class);
        AgentMetricsService metricsService = mock(AgentMetricsService.class);
        SupervisorGovernanceService governanceService = mock(SupervisorGovernanceService.class);
        AgentPermissionService permissionService = mock(AgentPermissionService.class);
        DistributedAgentProperties properties = new DistributedAgentProperties();
        properties.getAsyncRuntime().setWorkerId("workflow-subagent-lease-test");
        properties.getAsyncRuntime().setLeaseTimeoutSeconds(1L);
        properties.getAsyncRuntime().setChildTaskTimeoutMs(10_000L);
        when(governanceService.extractGovernanceMetadata(anyMap())).thenReturn(Map.of());

        AtomicReference<String> currentLeaseOwner = new AtomicReference<>();
        AtomicInteger mapperUpdates = new AtomicInteger();
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            LambdaUpdateWrapper<AgentAsyncWorkflowEntity> update = invocation.getArgument(1);
            mapperUpdates.incrementAndGet();
            update.getParamNameValuePairs().values().stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(value -> value.startsWith("workflow-subagent-lease-test-"))
                    .findFirst()
                    .ifPresent(currentLeaseOwner::set);
            return 1;
        }).when(workflowMapper).update(isNull(), any(LambdaUpdateWrapper.class));

        SupervisorTaskRequest request = new SupervisorTaskRequest(
                "session-2", "user-2", "workflow-task-2", "trace-2", "find listings", Map.of(), "web", false);
        ObjectMapper objectMapper = new ObjectMapper();
        AgentAsyncWorkflowEntity pending = new AgentAsyncWorkflowEntity();
        pending.setId(2L);
        pending.setAsyncWorkflowId("async-workflow-2");
        pending.setSessionId("session-2");
        pending.setTaskId("workflow-task-2");
        pending.setTraceId("trace-2");
        pending.setUserId("user-2");
        pending.setStatus("ACCEPTED");
        pending.setCancelRequested(0);
        pending.setAttemptCount(0);
        pending.setOriginalRequestJson(objectMapper.writeValueAsString(request));
        when(workflowMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(pending));
        when(workflowMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            AgentAsyncWorkflowEntity running = new AgentAsyncWorkflowEntity();
            running.setId(2L);
            running.setAsyncWorkflowId("async-workflow-2");
            running.setSessionId("session-2");
            running.setTaskId("workflow-task-2");
            running.setTraceId("trace-2");
            running.setUserId("user-2");
            running.setStatus("RUNNING");
            running.setCancelRequested(0);
            running.setAttemptCount(1);
            running.setLeaseOwner(currentLeaseOwner.get());
            running.setOriginalRequestJson(objectMapper.writeValueAsString(request));
            return running;
        });

        CountDownLatch subAgentCallStarted = new CountDownLatch(1);
        CountDownLatch allowSubAgentCallToFinish = new CountDownLatch(1);
        CountDownLatch workflowExecutionFinished = new CountDownLatch(1);
        RegisteredAgentDescriptor descriptor = listingAgent();
        AgentTaskInvokeRequest childRequest = new AgentTaskInvokeRequest(
                "session-2", "workflow-task-2", null, "trace-2", "supervisor-agent", "listing-agent",
                "listing.search", "listing", "find listings", Map.of(), List.of(), List.of(),
                "text", "idempotency-2", false);
        AgentTaskInvokeResponse childResponse = new AgentTaskInvokeResponse(
                "session-2", "workflow-task-2", "listing-agent", "COMPLETED", Map.of("answer", "done"),
                List.of(), List.of(), "done", "trace-2");
        when(a2aAgentClient.invoke(eq(descriptor), eq(childRequest))).thenAnswer(invocation -> {
            subAgentCallStarted.countDown();
            if (!allowSubAgentCallToFinish.await(8, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release the sub-agent call");
            }
            return childResponse;
        });

        A2aExecutionService a2aExecutionService = new A2aExecutionService(
                a2aAgentClient, streamService, permissionService, properties);
        when(supervisorWorkflowService.startWorkflow(any())).thenAnswer(invocation -> {
            try {
                assertThat(a2aExecutionService.execute(descriptor, childRequest, "single_agent", Map.of()))
                        .isSameAs(childResponse);
                return new SupervisorTaskResponse("session-2", "workflow-task-2", "COMPLETED", "done",
                        List.of(), "trace-2", "listing-agent", Map.of());
            } finally {
                workflowExecutionFinished.countDown();
            }
        });

        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.initialize();
        SupervisorAsyncWorkflowService asyncWorkflowService = new SupervisorAsyncWorkflowService(
                supervisorWorkflowService, streamService, metricsService, governanceService,
                permissionService, workflowMapper, objectMapper, properties, executor);

        try {
            asyncWorkflowService.dispatchPendingWorkflows(1);

            assertThat(subAgentCallStarted.await(4, TimeUnit.SECONDS)).isTrue();
            awaitAtLeastThreeMapperUpdates(mapperUpdates);
            assertThat(currentLeaseOwner.get()).startsWith("workflow-subagent-lease-test-");
            assertThat(mapperUpdates.get())
                    .as("lease recovery, claim, and at least one heartbeat while the A2A sub-agent is blocked")
                    .isGreaterThanOrEqualTo(3);

            allowSubAgentCallToFinish.countDown();
            assertThat(workflowExecutionFinished.await(4, TimeUnit.SECONDS)).isTrue();
        } finally {
            allowSubAgentCallToFinish.countDown();
        }
    }

    private void awaitAtLeastThreeMapperUpdates(AtomicInteger mapperUpdates) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (mapperUpdates.get() < 3 && System.nanoTime() < deadline) {
            Thread.sleep(25L);
        }
    }

    private RegisteredAgentDescriptor listingAgent() {
        AgentCard card = new AgentCard("listing-agent", "Listing", "test", "1.0", List.of(),
                List.of("listing"), false, false, "http://localhost:9999/a2a", List.of("text"), List.of("text"));
        return new RegisteredAgentDescriptor("listing-agent", "http://localhost:9999",
                "/.well-known/agent.json", "/a2a", AgentRuntimeType.ALIBABA_A2A,
                AgentDescriptorSource.DISCOVERED_CARD, card);
    }
}
