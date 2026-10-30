package com.bkanent.agent.stream;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import static org.mockito.Mockito.verify;
import org.mockito.ArgumentCaptor;

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
    void resolvesResumeEventIdWithinItsSession() {
        AgentEventAuditMapper mapper = mock(AgentEventAuditMapper.class);
        AgentEventAuditEntity cursor = new AgentEventAuditEntity();
        cursor.setId(19L);
        when(mapper.selectOne(any())).thenReturn(cursor);
        SessionEventAuditService service = new SessionEventAuditService(
                new DistributedAgentProperties(), mapper, new ObjectMapper());

        assertThat(service.sequenceForEventId("session", "event-19")).isEqualTo(19L);
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

    @Test
    void parentTaskReplayIncludesOnlyEventsDirectlyLinkedToThatParent() {
        AgentEventAuditMapper mapper = mock(AgentEventAuditMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.selectCount(any())).thenReturn(0L);
        AgentEventAuditEntity child = persisted("session-1", "child-1", 51L,
                "{\"parentTaskId\":\"parent-1\",\"visibility\":\"progress\"}");
        AgentEventAuditEntity unrelated = persisted("session-1", "child-2", 52L,
                "{\"parentTaskId\":\"parent-2\",\"visibility\":\"progress\"}");
        when(mapper.selectList(any())).thenReturn(java.util.List.of(child, unrelated));
        SessionEventAuditService service = new SessionEventAuditService(
                new DistributedAgentProperties(), mapper, new ObjectMapper());

        assertThat(service.replay("session-1", "parent-1", null, 0L, 10))
                .extracting(SessionStreamEvent::taskId)
                .containsExactly("child-1");

        ArgumentCaptor<Wrapper<AgentEventAuditEntity>> query = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(query.capture());
        LambdaQueryWrapper<?> lambda = (LambdaQueryWrapper<?>) query.getValue();
        assertThat(lambda.getSqlSegment()).contains("JSON_UNQUOTE", "JSON_EXTRACT", "parentTaskId", "session_id", "task_id");
        assertThat(lambda.getParamNameValuePairs().values()).contains("session-1", "parent-1");
    }

    @Test
    void afterEventIdCursorIsScopedToItsSession() {
        AgentEventAuditMapper mapper = mock(AgentEventAuditMapper.class);
        when(mapper.selectCount(any())).thenReturn(0L);
        AgentEventAuditEntity cursor = new AgentEventAuditEntity();
        cursor.setId(20L);
        when(mapper.selectOne(any())).thenReturn(cursor);
        when(mapper.selectList(any())).thenReturn(java.util.List.of());
        SessionEventAuditService service = new SessionEventAuditService(
                new DistributedAgentProperties(), mapper, new ObjectMapper());

        service.replay("session-1", null, "event-from-another-session", null, 10);

        ArgumentCaptor<Wrapper<AgentEventAuditEntity>> query = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectOne(query.capture());
        LambdaQueryWrapper<?> lambda = (LambdaQueryWrapper<?>) query.getValue();
        assertThat(lambda.getSqlSegment()).contains("session_id", "event_id");
        assertThat(lambda.getParamNameValuePairs().values()).contains("session-1", "event-from-another-session");
    }

    @Test
    void actualH2OwnerPredicatesHideCrossSessionWrongRunLegacyAndMaliciousFilters() throws Exception {
        var datasource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(datasource);
        java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
        while (!java.nio.file.Files.exists(root.resolve("sql/migrations/20261013_mysql_init_bootstrap.sql"))) root = root.getParent();
        var ddl = java.util.regex.Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS agent_event_audit \\(.*?;")
                .matcher(java.nio.file.Files.readString(root.resolve("sql/migrations/20261013_mysql_init_bootstrap.sql")));
        assertThat(ddl.find()).isTrue();
        jdbc.execute(ddl.group()); // Real checked-in audit schema, not hand-copied table columns.
        var bean = new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();
        bean.setDataSource(datasource);
        bean.setConfiguration(new MybatisConfiguration());
        var global = new com.baomidou.mybatisplus.core.config.GlobalConfig();
        global.setMetaObjectHandler(new com.baomidou.mybatisplus.core.handlers.MetaObjectHandler() {
            public void insertFill(org.apache.ibatis.reflection.MetaObject object) {
                strictInsertFill(object, "createdAt", java.time.LocalDateTime.class, java.time.LocalDateTime.now());
                strictInsertFill(object, "updatedAt", java.time.LocalDateTime.class, java.time.LocalDateTime.now());
            }
            public void updateFill(org.apache.ibatis.reflection.MetaObject object) {
                strictUpdateFill(object, "updatedAt", java.time.LocalDateTime.class, java.time.LocalDateTime.now());
            }
        });
        bean.setGlobalConfig(global);
        var sqlFactory = bean.getObject();
        sqlFactory.getConfiguration().addMapper(AgentEventAuditMapper.class);
        var sqlSession = new org.mybatis.spring.SqlSessionTemplate(sqlFactory);
        var audit = new SessionEventAuditService(new DistributedAgentProperties(),
                sqlSession.getMapper(AgentEventAuditMapper.class), new ObjectMapper());
        long now = System.currentTimeMillis();
        for (String[] row : java.util.List.of(
                new String[]{"owned-session", "owned-run", "owned"},
                new String[]{"another-session", "owned-run", "wrong-session"},
                new String[]{"owned-session", "foreign-run", "wrong-run"},
                new String[]{"foreign-session", "foreign-run", "foreign"},
                new String[]{"owned-session", null, "legacy"})) {
            jdbc.update("INSERT INTO agent_event_audit(session_id,task_id,agent_id,event_type,content,metadata_json,event_timestamp) VALUES (?,?,?,?,?,?,?)",
                    row[0], row[1], "agent", "agent.completed", row[2], "{\"userId\":\"2\"}", now);
        }
        var scopes = java.util.List.of(new com.bkanent.agent.orchestration.OrchestrationStore.RunScope("owned-run", "owned-session"));
        assertThat(audit.queryOwned(scopes, null, null, null, null, null, null, null, true, 200))
                .extracting(com.bkanent.agent.model.distributed.SessionEventAuditView::content).containsExactly("owned");
        assertThat(audit.queryOwned(scopes, "foreign-run", null, null, null, null, null, null, true, 200)).isEmpty();
        assertThat(audit.queryOwned(scopes, "owned-run' OR 1=1 --", null, null, null, null, null, null, true, 200)).isEmpty();
        assertThat(audit.queryOwned(java.util.List.of(), null, null, null, null, null, null, null, true, 200)).isEmpty();

        var auth = mock(com.bkanent.common.rpc.AuthPermissionRpcService.class);
        when(auth.resolvePrincipal("actual-token")).thenReturn(new com.bkanent.common.rpc.AuthenticatedPrincipal(2L));
        when(auth.hasPermission(2L, "agent.audit.read")).thenReturn(true);
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader(com.bkanent.common.rpc.AuthPrincipalContext.MARKER_HEADER, com.bkanent.common.rpc.AuthPrincipalContext.MARKER_VALUE);
        request.addHeader(com.bkanent.common.rpc.AuthPrincipalContext.USER_ID_HEADER, "2");
        request.addHeader("Authorization", "Bearer actual-token");
        var principal = new com.bkanent.agent.security.AgentPrincipalContext(request, auth);
        var store = mock(com.bkanent.agent.orchestration.OrchestrationStore.class);
        when(store.ownedRunScopes("2")).thenReturn(scopes);
        var ownership = mock(com.bkanent.agent.service.SupervisorSessionOwnershipService.class);
        var permissions = new com.bkanent.agent.service.AgentPermissionService(auth, mock(com.bkanent.agent.service.PermissionAuditService.class));
        var query = new com.bkanent.agent.service.SupervisorEventAuditQueryService(audit, principal, permissions, store, ownership);
        assertThat(query.query(null, null, null, null, null, null, null, true, 200))
                .extracting(com.bkanent.agent.model.distributed.SessionEventAuditView::content).containsExactly("owned");
        verify(ownership, org.mockito.Mockito.times(2)).assertOwned("owned-session", "2");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> query.queryBusinessDiagnostics(null, null, null, null, null, null, null, true, 200))
                .hasMessageContaining("permission denied");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_event_audit", Integer.class)).isEqualTo(5);
        when(auth.hasPermission(2L, "agent.audit.manage")).thenReturn(true);
        assertThat(query.queryBusinessDiagnostics(null, null, null, null, null, null, null, true, 200))
                .extracting(com.bkanent.agent.model.distributed.SessionEventAuditView::content)
                .contains("owned", "foreign", "wrong-session", "wrong-run", "legacy", "Business event audit query");
        assertThat(jdbc.queryForObject("SELECT metadata_json FROM agent_event_audit WHERE event_type='security.audit_access'", String.class))
                .contains("actorId", "2", "legacy-rpc-unversioned");
        org.mockito.Mockito.doThrow(new com.bkanent.agent.security.AgentPrincipalException("foreign owner"))
                .when(ownership).assertOwned("owned-session", "2");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> query.query(null, null, null, null, null, null, null, true, 200))
                .isInstanceOf(com.bkanent.agent.security.AgentPrincipalException.class);
    }

    @Test
    void requiredManagementAuditNeverFallsBackOnPersistenceFailure() {
        var mapper = mock(AgentEventAuditMapper.class);
        when(mapper.selectCount(any())).thenReturn(0L);
        org.mockito.Mockito.doThrow(new IllegalStateException("audit database down")).when(mapper).insert(any(AgentEventAuditEntity.class));
        var service = new SessionEventAuditService(new DistributedAgentProperties(), mapper, new ObjectMapper());
        var event = new SessionStreamEvent("business-audit", null, "supervisor", "security.audit_access", "access", Map.of(), null, System.currentTimeMillis());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.recordRequired(event)).hasMessage("audit database down");
    }

    private AgentEventAuditEntity persisted(String sessionId, String taskId, long sequence, String metadata) {
        AgentEventAuditEntity entity = new AgentEventAuditEntity();
        entity.setId(sequence);
        entity.setEventId("event-" + sequence);
        entity.setSessionId(sessionId);
        entity.setTaskId(taskId);
        entity.setAgentId("agent");
        entity.setEventType("agent.delta");
        entity.setContent("chunk");
        entity.setMetadataJson(metadata);
        entity.setEventTimestamp(sequence);
        return entity;
    }
}
