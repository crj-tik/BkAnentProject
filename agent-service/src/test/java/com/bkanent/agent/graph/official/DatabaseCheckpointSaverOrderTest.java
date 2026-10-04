package com.bkanent.agent.graph.official;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.bkanent.agent.entity.AgentWorkflowCheckpointEntity;
import com.bkanent.agent.mapper.AgentWorkflowCheckpointMapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseCheckpointSaverOrderTest {
    @Test
    void reloadUsesLatestCheckpointFirstAsRequiredByMemorySaver() throws Exception {
        var mapper = new ObjectMapper(); var persistence = mock(AgentWorkflowCheckpointMapper.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), AgentWorkflowCheckpointEntity.class);
        var latest = row(mapper, 2, "WAITING_USER_INPUT"); var first = row(mapper, 1, "RUNNING");
        when(persistence.selectList(any())).thenAnswer(invocation -> {
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<?> query = invocation.getArgument(0);
            assertThat(query.getSqlSegment()).contains("checkpoint_version DESC");
            return List.of(latest, first);
        });
        var saver = new DatabaseCheckpointSaver(persistence, mapper, "llm-tools-v1");
        assertThat(saver.get(RunnableConfig.builder().threadId("run").build()).orElseThrow().getState())
                .containsEntry("workflowStatus", "WAITING_USER_INPUT");
    }
    private AgentWorkflowCheckpointEntity row(ObjectMapper mapper, int version, String status) throws Exception {
        var row = new AgentWorkflowCheckpointEntity(); row.setId((long) version); row.setTaskId("run"); row.setCheckpointVersion(version);
        row.setSnapshotJson(mapper.writeValueAsString(Map.of("version", 1, "graphName", "llm-tools-v1", "id", "checkpoint-" + version,
                "nodeId", "PauseInput", "nextNodeId", "Model", "state", Map.of("taskId", "run", "workflowStatus", status))));
        return row;
    }
}
