package com.bkanent.media.a2a;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.KeyStrategyFactoryBuilder;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.action.NodeActionWithConfig;
import com.alibaba.cloud.ai.graph.agent.BaseAgent;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.internal.node.Node;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.bkanent.media.service.MediaAgentService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class MediaOfficialA2aAgent extends BaseAgent {

    private static final String GRAPH_ID = "media-official-a2a-agent";
    private static final String NODE_INVOKE = "invoke_media";
    private static final String KEY_MESSAGES = "messages";
    private static final String KEY_INPUT = "input";
    private static final String KEY_OUTPUT = "output";

    private final MediaAgentService mediaAgentService;
    private final ObjectMapper objectMapper;

    public MediaOfficialA2aAgent(MediaAgentService mediaAgentService,
                                 ObjectMapper objectMapper) {
        super(
                "media-agent",
                "负责媒体任务生成与发布前素材准备",
                false,
                false,
                KEY_OUTPUT,
                new ReplaceStrategy()
        );
        this.mediaAgentService = mediaAgentService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Node asNode(boolean includeContents, boolean returnReasoningContents) {
        throw new UnsupportedOperationException("media official a2a agent is exposed as standalone server only");
    }

    @Override
    protected StateGraph initGraph() throws GraphStateException {
        StateGraph stateGraph = new StateGraph(GRAPH_ID, buildKeyStrategyFactory());
        stateGraph.addNode(NODE_INVOKE, invokeNode());
        stateGraph.addEdge(StateGraph.START, NODE_INVOKE);
        stateGraph.addEdge(NODE_INVOKE, StateGraph.END);
        return stateGraph;
    }

    private KeyStrategyFactory buildKeyStrategyFactory() {
        Map<String, KeyStrategy> strategies = new LinkedHashMap<>();
        strategies.put(KEY_MESSAGES, new ReplaceStrategy());
        strategies.put(KEY_INPUT, new ReplaceStrategy());
        strategies.put(KEY_OUTPUT, new ReplaceStrategy());
        return new KeyStrategyFactoryBuilder()
                .addStrategies(strategies)
                .build();
    }

    private AsyncNodeActionWithConfig invokeNode() {
        NodeActionWithConfig action = (state, config) -> {
            String instruction = state.value(KEY_INPUT, (String) null);
            AgentTaskInvokeResponse response = mediaAgentService.invoke(buildInvokeRequest(instruction, config));
            return Map.of(KEY_OUTPUT, serializeResponse(response));
        };
        return AsyncNodeActionWithConfig.node_async(action);
    }

    private AgentTaskInvokeRequest buildInvokeRequest(String instruction, RunnableConfig config) {
        Map<String, Object> metadata = config.metadata().orElse(Map.of());
        String sessionId = stringMetadata(metadata, "sessionId");
        String taskId = stringMetadata(metadata, "taskId");
        String traceId = stringMetadata(metadata, "traceId");
        String sourceAgentId = stringMetadata(metadata, "sourceAgentId");
        String targetAgentId = stringMetadata(metadata, "targetAgentId");
        String intent = stringMetadata(metadata, "intent");
        String domain = stringMetadata(metadata, "domain");
        return new AgentTaskInvokeRequest(
                StringUtils.hasText(sessionId) ? sessionId : UUID.randomUUID().toString(),
                StringUtils.hasText(taskId) ? taskId : UUID.randomUUID().toString(),
                null,
                StringUtils.hasText(traceId) ? traceId : UUID.randomUUID().toString(),
                StringUtils.hasText(sourceAgentId) ? sourceAgentId : "remote-a2a-client",
                StringUtils.hasText(targetAgentId) ? targetAgentId : "media-agent",
                StringUtils.hasText(intent) ? intent : "media.generate_task",
                StringUtils.hasText(domain) ? domain : "media",
                instruction,
                structuredContext(metadata),
                List.of(),
                List.of(),
                "text",
                UUID.randomUUID().toString(),
                false
        );
    }

    private Map<String, Object> structuredContext(Map<String, Object> metadata) {
        Object context = metadata.get("structuredContext");
        if (context instanceof Map<?, ?> map) {
            Map<String, Object> casted = new LinkedHashMap<>();
            map.forEach((key, value) -> casted.put(String.valueOf(key), value));
            return casted;
        }
        return Map.of();
    }

    private String stringMetadata(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }

    private String serializeResponse(AgentTaskInvokeResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize media agent response", exception);
        }
    }
}
