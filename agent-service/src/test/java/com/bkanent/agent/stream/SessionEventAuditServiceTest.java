package com.bkanent.agent.stream;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.bkanent.agent.config.DistributedAgentProperties;
import com.bkanent.agent.entity.AgentEventAuditEntity;
import com.bkanent.agent.mapper.AgentEventAuditMapper;
import com.bkanent.common.agent.SessionStreamEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionEventAuditServiceTest {

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "session-event-audit-test"),
                AgentEventAuditEntity.class);
    }

    @Test
    void assignsStableEventIdAndSequenceBeforePublication() {
        AgentEventAuditMapper mapper = mock(AgentEventAuditMapper.class);
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.selectList(any())).thenReturn(java.util.List.of());
        doAnswer(invocation -> {
            AgentEventAuditEntity entity = invocation.getArgument(0);
            entity.setId(42L);
            return 1;
        }).when(mapper).insert(any(AgentEventAuditEntity.class));
        SessionEventAuditService service = new SessionEventAuditService(
                new DistributedAgentProperties(), mapper, new ObjectMapper());

        SessionStreamEvent result = service.recordAndEnrich(new SessionStreamEvent(
                "session", "task", "agent", "agent.delta", "chunk", Map.of(), "trace", 1L));

        assertThat(result.eventId()).isNotBlank();
        assertThat(result.sequence()).isEqualTo(42L);
        assertThat(result.phase()).isEqualTo("agent_execution");
        assertThat(result.terminal()).isFalse();
        assertThat(result.visibility()).isEqualTo("progress");
    }

    @Test
    void reusesExistingSequenceForDuplicateEventId() {
        AgentEventAuditMapper mapper = mock(AgentEventAuditMapper.class);
        AgentEventAuditEntity existing = new AgentEventAuditEntity();
        existing.setId(7L);
        existing.setEventId("event-7");
        when(mapper.selectOne(any())).thenReturn(existing);
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.selectList(any())).thenReturn(java.util.List.of());
        SessionEventAuditService service = new SessionEventAuditService(
                new DistributedAgentProperties(), mapper, new ObjectMapper());

        SessionStreamEvent result = service.recordAndEnrich(new SessionStreamEvent(
                "session", "task", "agent", "agent.delta", "chunk", Map.of(), "trace", 1L,
                "event-7", null, null, null, null, null, false, "progress"));

        assertThat(result.eventId()).isEqualTo("event-7");
        assertThat(result.sequence()).isEqualTo(7L);
    }

    @Test
    void replaysTerminalStructuredResultFromPersistedMetadata() {
        AgentEventAuditMapper mapper = mock(AgentEventAuditMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.selectCount(any())).thenReturn(0L);
        AgentEventAuditEntity persisted = new AgentEventAuditEntity();
        doAnswer(invocation -> {
            AgentEventAuditEntity entity = invocation.getArgument(0);
            entity.setId(43L);
            persisted.setId(43L);
            persisted.setEventId(entity.getEventId());
            persisted.setSessionId(entity.getSessionId());
            persisted.setTaskId(entity.getTaskId());
            persisted.setAgentId(entity.getAgentId());
            persisted.setEventType(entity.getEventType());
            persisted.setStage(entity.getStage());
            persisted.setContent(entity.getContent());
            persisted.setMetadataJson(entity.getMetadataJson());
            persisted.setTraceId(entity.getTraceId());
            persisted.setEventTimestamp(entity.getEventTimestamp());
            persisted.setArchived(0);
            return 1;
        }).when(mapper).insert(any(AgentEventAuditEntity.class));
        when(mapper.selectList(any())).thenReturn(java.util.List.of(persisted));
        SessionEventAuditService service = new SessionEventAuditService(
                new DistributedAgentProperties(), mapper, new ObjectMapper());

        Map<String, Object> terminalResult = Map.of(
                "status", "COMPLETED",
                "remoteTaskId", "remote-task-1",
                "artifactIds", java.util.List.of("artifact-1"),
                "structuredOutput", Map.of("contentType", "listing"));
        service.recordAndEnrich(new SessionStreamEvent(
                "session", "task", "agent", "agent.completed", "done",
                Map.of("terminal", true, "result", terminalResult), "trace", 1L));

        assertThat(service.replay("session", "task", null, null, 10))
                .singleElement()
                .extracting(event -> event.metadata().get("result"))
                .isEqualTo(terminalResult);
    }
}
