package com.bkanent.agent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.bkanent.agent.config.DistributedAgentProperties;
import com.bkanent.agent.entity.AgentAsyncTaskEntity;
import com.bkanent.agent.mapper.AgentAsyncTaskMapper;
import com.bkanent.agent.model.distributed.SupervisorTaskResponse;
import com.bkanent.agent.registry.AgentRegistry;
import com.bkanent.agent.stream.SessionStreamService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SupervisorAsyncTaskStatusTest {
    private static final String TASK_ID = "task-1";

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "async-status-test"),
                AgentAsyncTaskEntity.class);
    }

    @Test
    void ownerCanReadCompletedStatusAndValidPersistedResult() throws Exception {
        var entity = completedEntity();
        var result = new SupervisorTaskResponse("session-1", TASK_ID, "COMPLETED", "done", List.of(),
                "trace-1", "supervisor-agent", Map.of());
        entity.setResultJson(new ObjectMapper().writeValueAsString(result));
        var response = service(entity).queryStatus("async-1", "42");

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.result()).isEqualTo(result);
        assertThat(response.errorMessage()).isNull();
    }

    @Test
    void malformedOrMismatchedPersistedResultDoesNotTurnStatusQueryIntoServerError() {
        var malformed = completedEntity();
        malformed.setResultJson("{broken");
        var malformedResponse = service(malformed).queryStatus("async-1", "42");
        assertThat(malformedResponse.status()).isEqualTo("COMPLETED");
        assertThat(malformedResponse.result()).isNull();
        assertThat(malformedResponse.errorMessage()).isEqualTo("ASYNC_RESULT_UNAVAILABLE");

        var mismatched = completedEntity();
        mismatched.setResultJson("{\"taskId\":\"other-run\",\"status\":\"COMPLETED\"}");
        var mismatchedResponse = service(mismatched).queryStatus("async-1", "42");
        assertThat(mismatchedResponse.result()).isNull();
        assertThat(mismatchedResponse.errorMessage()).isEqualTo("ASYNC_RESULT_UNAVAILABLE");
    }

    @Test
    void currentMemorySourceDenialKeepsStatusVisibleAndSuppressesStoredResult() throws Exception {
        var entity = completedEntity();
        entity.setResultJson(new ObjectMapper().writeValueAsString(new SupervisorTaskResponse(
                "session-1", TASK_ID, "COMPLETED", "private result", List.of(), "trace-1", "supervisor-agent", Map.of())));
        var runner = mock(com.bkanent.agent.orchestration.SupervisorToolLoopRunner.class);
        when(runner.hasRun(TASK_ID)).thenReturn(true);
        when(runner.current(TASK_ID, "42")).thenThrow(new com.bkanent.common.memory.MemoryException(
                com.bkanent.common.memory.MemoryErrorCode.WITHDRAWN));

        var response = service(entity, runner).queryStatus("async-1", "42");

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.result()).isNull();
        assertThat(response.errorMessage()).isEqualTo("ASYNC_RESULT_UNAVAILABLE");
    }

    private SupervisorAsyncTaskService service(AgentAsyncTaskEntity entity) {
        return service(entity, null);
    }

    private SupervisorAsyncTaskService service(AgentAsyncTaskEntity entity,
            com.bkanent.agent.orchestration.SupervisorToolLoopRunner runner) {
        AgentAsyncTaskMapper mapper = mock(AgentAsyncTaskMapper.class);
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);
        var service = new SupervisorAsyncTaskService(mock(AgentRegistry.class), mock(A2aExecutionService.class),
                mock(SupervisorTaskService.class), mock(SessionStreamService.class), mock(AgentMetricsService.class),
                mock(SupervisorGovernanceService.class), mock(AgentPermissionService.class), mapper,
                new ObjectMapper(), new DistributedAgentProperties(), mock(ThreadPoolTaskExecutor.class));
        if (runner != null) service.setToolLoopRunner(runner);
        return service;
    }

    private AgentAsyncTaskEntity completedEntity() {
        var entity = new AgentAsyncTaskEntity();
        entity.setAsyncTaskId("async-1");
        entity.setSessionId("session-1");
        entity.setTaskId(TASK_ID);
        entity.setTraceId("trace-1");
        entity.setUserId("42");
        entity.setSelectedAgentId("supervisor-agent");
        entity.setMode("LOCAL_WORKFLOW");
        entity.setStatus("COMPLETED");
        return entity;
    }
}
