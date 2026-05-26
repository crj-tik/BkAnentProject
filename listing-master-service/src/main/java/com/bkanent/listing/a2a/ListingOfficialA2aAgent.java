package com.bkanent.listing.a2a;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.KeyStrategyFactoryBuilder;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.agent.BaseAgent;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.internal.node.Node;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.bkanent.listing.service.ListingAgentService;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class ListingOfficialA2aAgent extends BaseAgent {

    private static final String GRAPH_ID = "listing-official-a2a-agent";
    private static final String NODE_SEARCH = "search_listing";
    private static final String KEY_MESSAGES = "messages";
    private static final String KEY_INPUT = "input";
    private static final String KEY_OUTPUT = "output";

    private final ListingAgentService listingAgentService;

    public ListingOfficialA2aAgent(ListingAgentService listingAgentService) {
        super(
                "listing-agent",
                "负责房源检索、推荐与摘要",
                false,
                false,
                KEY_OUTPUT,
                new ReplaceStrategy()
        );
        this.listingAgentService = listingAgentService;
    }

    @Override
    public Node asNode(boolean includeContents, boolean returnReasoningContents) {
        throw new UnsupportedOperationException("listing official a2a agent is exposed as standalone server only");
    }

    @Override
    protected StateGraph initGraph() throws GraphStateException {
        StateGraph stateGraph = new StateGraph(GRAPH_ID, buildKeyStrategyFactory());
        stateGraph.addNode(NODE_SEARCH, searchNode());
        stateGraph.addEdge(StateGraph.START, NODE_SEARCH);
        stateGraph.addEdge(NODE_SEARCH, StateGraph.END);
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

    private AsyncNodeAction searchNode() {
        NodeAction action = state -> {
            String instruction = state.value(KEY_INPUT, (String) null);
            AgentTaskInvokeResponse response = listingAgentService.invoke(buildInvokeRequest(instruction));
            return Map.of(KEY_OUTPUT, renderOutput(response));
        };
        return AsyncNodeAction.node_async(action);
    }

    private AgentTaskInvokeRequest buildInvokeRequest(String instruction) {
        return new AgentTaskInvokeRequest(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                null,
                UUID.randomUUID().toString(),
                "remote-a2a-client",
                "listing-agent",
                "listing.summary",
                "listing",
                instruction,
                Map.of(),
                List.of(),
                List.of(),
                "text",
                UUID.randomUUID().toString(),
                false
        );
    }

    private String renderOutput(AgentTaskInvokeResponse response) {
        StringBuilder builder = new StringBuilder();
        if (StringUtils.hasText(response.summary())) {
            builder.append(response.summary().trim());
        }
        Object listingsValue = response.structuredOutput() == null ? null : response.structuredOutput().get("listings");
        if (!(listingsValue instanceof List<?> listings) || CollectionUtils.isEmpty(listings)) {
            return builder.length() == 0 ? "未检索到房源结果。" : builder.toString();
        }
        if (builder.length() > 0) {
            builder.append(System.lineSeparator());
        }
        int index = 1;
        for (Object listingObject : listings) {
            if (!(listingObject instanceof Map<?, ?> listingItem)) {
                continue;
            }
            Object listing = listingItem.get("listing");
            if (!(listing instanceof Map<?, ?> listingMap)) {
                continue;
            }
            builder.append(index++)
                    .append(". ")
                    .append(stringValue(listingMap.get("title"), "未命名房源"));
            Object community = listingMap.get("communityName");
            if (StringUtils.hasText(stringValue(community, null))) {
                builder.append(" | ").append(stringValue(community, null));
            }
            Object district = listingMap.get("district");
            if (StringUtils.hasText(stringValue(district, null))) {
                builder.append(" | ").append(stringValue(district, null));
            }
            Object totalPrice = listingMap.get("totalPrice");
            if (totalPrice != null) {
                builder.append(" | 总价 ").append(totalPrice);
            }
            builder.append(System.lineSeparator());
        }
        return builder.toString().trim();
    }

    private String stringValue(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : fallback;
    }
}
