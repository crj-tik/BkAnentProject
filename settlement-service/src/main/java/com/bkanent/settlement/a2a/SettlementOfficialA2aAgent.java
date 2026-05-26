package com.bkanent.settlement.a2a;

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
import com.bkanent.settlement.service.SettlementAgentService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class SettlementOfficialA2aAgent extends BaseAgent {

    private static final String GRAPH_ID = "settlement-official-a2a-agent";
    private static final String NODE_INVOKE = "invoke_settlement";
    private static final String KEY_MESSAGES = "messages";
    private static final String KEY_INPUT = "input";
    private static final String KEY_OUTPUT = "output";

    private final SettlementAgentService settlementAgentService;
    private final ObjectMapper objectMapper;

    public SettlementOfficialA2aAgent(SettlementAgentService settlementAgentService,
                                      ObjectMapper objectMapper) {
        super("settlement-agent", "负责结算计算、批次准备与月度汇总", false, false, KEY_OUTPUT, new ReplaceStrategy());
        this.settlementAgentService = settlementAgentService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Node asNode(boolean includeContents, boolean returnReasoningContents) {
        throw new UnsupportedOperationException("settlement official a2a agent is exposed as standalone server only");
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
        return new KeyStrategyFactoryBuilder().addStrategies(strategies).build();
    }

    private AsyncNodeActionWithConfig invokeNode() {
        NodeActionWithConfig action = (state, config) -> {
            String instruction = state.value(KEY_INPUT, (String) null);
            AgentTaskInvokeResponse response = settlementAgentService.invoke(buildInvokeRequest(instruction, config));
            return Map.of(KEY_OUTPUT, serializeResponse(response));
        };
        return AsyncNodeActionWithConfig.node_async(action);
    }

    private AgentTaskInvokeRequest buildInvokeRequest(String instruction, RunnableConfig config) {
        Map<String, Object> metadata = config.metadata().orElse(Map.of());
        return new AgentTaskInvokeRequest(
                stringMetadata(metadata, "sessionId", UUID.randomUUID().toString()),
                stringMetadata(metadata, "taskId", UUID.randomUUID().toString()),
                null,
                stringMetadata(metadata, "traceId", UUID.randomUUID().toString()),
                stringMetadata(metadata, "sourceAgentId", "remote-a2a-client"),
                stringMetadata(metadata, "targetAgentId", "settlement-agent"),
                stringMetadata(metadata, "intent", "settlement.prepare"),
                stringMetadata(metadata, "domain", "settlement"),
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

    private String stringMetadata(Map<String, Object> metadata, String key, String defaultValue) {
        Object value = metadata.get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : defaultValue;
    }

    private String serializeResponse(AgentTaskInvokeResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize settlement agent response", exception);
        }
    }
}
