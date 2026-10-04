package com.bkanent.agent.orchestration;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.bkanent.agent.graph.official.*;
import com.bkanent.agent.mapper.AgentWorkflowCheckpointMapper;
import com.bkanent.agent.mapper.AgentWorkflowApprovalClaimMapper;
import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.agent.stream.SessionStreamService;
import com.bkanent.common.agent.SkillSelection;
import com.bkanent.common.skill.SkillDefinition;
import com.bkanent.common.skill.core.SkillRegistry;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.reflection.MetaObject;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in MySQL test: actual mapper, SQL journal and JSON checkpoint reload in a fresh graph/saver. */
class SupervisorDockerDatabaseTest {
    @Test
    void freshRunnerResumesPinnedInputAndReusesCompletedCall() throws Exception {
        String url = System.getenv("BK_AGENT_DB_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "Docker acceptance database is not configured");
        var datasource = new DriverManagerDataSource(url, System.getenv("BK_AGENT_DB_USER"), System.getenv("BK_AGENT_DB_PASSWORD"));
        var jdbc = new JdbcTemplate(datasource);
        var mapper = new ObjectMapper().findAndRegisterModules();
        var configuration = new MybatisConfiguration();
        var global = new GlobalConfig();
        global.setMetaObjectHandler(new MetaObjectHandler() {
            public void insertFill(MetaObject object) {
                strictInsertFill(object, "createdAt", LocalDateTime.class, LocalDateTime.now());
                strictInsertFill(object, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }
            public void updateFill(MetaObject object) { strictUpdateFill(object, "updatedAt", LocalDateTime.class, LocalDateTime.now()); }
        });
        var bean = new MybatisSqlSessionFactoryBean(); bean.setDataSource(datasource); bean.setConfiguration(configuration); bean.setGlobalConfig(global);
        var sqlFactory = bean.getObject();
        sqlFactory.getConfiguration().addMapper(AgentWorkflowCheckpointMapper.class); sqlFactory.getConfiguration().addMapper(AgentWorkflowApprovalClaimMapper.class);
        var session = new SqlSessionTemplate(sqlFactory);
        var saverFactory = new DatabaseCheckpointSaverFactory(session.getMapper(AgentWorkflowCheckpointMapper.class), mapper);
        var store = new OrchestrationStore(jdbc, mapper);
        var claims = new ApprovalResumeClaimStore(session.getMapper(AgentWorkflowApprovalClaimMapper.class), mapper);
        var skills = mock(SkillRegistry.class); var catalog = mock(SupervisorCapabilityCatalog.class); var model = mock(SupervisorModelTurn.class);
        var props = new SupervisorOrchestrationProperties();
        var definition = SkillDefinition.builder().name("find").description("find").domain("supervisor")
                .capabilities(new com.bkanent.common.skill.SkillCapabilityPolicy("inherit", List.of())).systemPrompt("pinned body").build();
        when(skills.getByName("find")).thenReturn(definition);
        when(skills.findOperationalSkills("supervisor")).thenReturn(List.of()); when(skills.findSupervisorSkills()).thenReturn(List.of());
        var callback = new org.springframework.ai.tool.ToolCallback() {
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() { return org.springframework.ai.tool.definition.ToolDefinition.builder()
                    .name("search").description("search").inputSchema("{\"type\":\"object\"}").build(); }
            public String call(String input) { return "actual result"; }
        };
        when(catalog.snapshot("1", true)).thenReturn(Map.of("local:search", new SupervisorCapability("local:search", "local", "search", "1", callback)));
        when(model.call(anyList(), anyList())).thenReturn(AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("question", "function", "request_input", "{\"question\":\"地区\"}"))).build());
        String id = "db-" + UUID.randomUUID();
        var firstFactory = new SupervisorToolLoopGraph(catalog, model, skills, store, mapper, props, mock(SessionStreamService.class), new OfficialSupervisorGraphSchema(), saverFactory);
        var firstRunner = new SupervisorToolLoopRunner(firstFactory, store, props, claims);
        assertThat(firstRunner.execute(new SupervisorTaskRequest("session", "1", id, id, "find", Map.of(), "api", false,
                new SkillSelection("find", "1"), null, null)).status()).isEqualTo("WAITING_USER_INPUT");
        firstRunner.close(); firstFactory.close();
        when(skills.getByName("find")).thenReturn(SkillDefinition.builder().name("find").description("new").domain("supervisor")
                .version("2").systemPrompt("changed body").build());
        when(model.call(anyList(), anyList())).thenAnswer(invocation -> {
            List<org.springframework.ai.chat.messages.Message> messages = invocation.getArgument(0);
            assertThat(messages.get(0).getText()).contains("pinned body").doesNotContain("changed body");
            return new AssistantMessage("done");
        });
        var restartedFactory = new SupervisorToolLoopGraph(catalog, model, skills, store, mapper, props, mock(SessionStreamService.class), new OfficialSupervisorGraphSchema(), saverFactory);
        var restarted = new SupervisorToolLoopRunner(restartedFactory, store, props, claims);
        try {
            var input = new SupervisorTaskRequest("session", "1", "input", id, "浦东", Map.of(), "api", false, null, id, null);
            var completed = restarted.execute(input);
            assertThat(completed.status()).isEqualTo("COMPLETED");
            assertThat(completed.governanceMetadata().get("rounds")).isEqualTo(2);
            assertThat(restarted.execute(input)).isEqualTo(completed);
            assertThat(saverFactory.create("official-supervisor").get(RunnableConfig.builder().threadId(id).build())).isEmpty();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_skill_snapshot WHERE run_id=?", Integer.class, id)).isEqualTo(1);
            // SQL-level identity and lease fencing remain valid across separate store instances.
            var secondStore = new OrchestrationStore(jdbc, mapper);
            assertThat(store.lease(id, "one", 10000)).isTrue();
            assertThat(secondStore.lease(id, "two", 10000)).isFalse();
            assertThatThrownBy(() -> secondStore.assertActive(id, "two")).hasMessage("RUN_LEASE_LOST"); store.release(id, "one");
        } finally { restarted.close(); restartedFactory.close(); }
    }
}
