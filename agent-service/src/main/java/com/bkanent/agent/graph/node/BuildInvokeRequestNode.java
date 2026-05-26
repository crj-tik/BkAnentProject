package com.bkanent.agent.graph.node;

import com.bkanent.agent.config.DistributedAgentProperties;
import com.bkanent.agent.graph.SupervisorGraphState;
import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.common.agent.A2aInvokeSupport;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class BuildInvokeRequestNode {

    private final DistributedAgentProperties distributedAgentProperties;

    public BuildInvokeRequestNode(DistributedAgentProperties distributedAgentProperties) {
        this.distributedAgentProperties = distributedAgentProperties;
    }

    public AgentTaskInvokeRequest build(SupervisorTaskRequest request,
                                        SupervisorGraphState graphState,
                                        String targetAgentId,
                                        String feedback,
                                        int retryCount) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (graphState.sharedContext() != null) {
            context.putAll(graphState.sharedContext());
        }
        context.putIfAbsent("userId", request.userId());
        context.putIfAbsent("keyword", request.userMessage());
        context.putIfAbsent("topK", 5);
        context.put("domain", graphState.domain());
        context.put("retryCount", retryCount);
        context.put("requestStream", Boolean.TRUE.equals(request.stream()));
        context.put("forceAsyncA2a", Boolean.TRUE.equals(request.stream()));
        if (StringUtils.hasText(feedback)) {
            context.put("approvalFeedback", feedback);
        }
        return new AgentTaskInvokeRequest(
                graphState.sessionId(),
                graphState.taskId(),
                null,
                graphState.traceId(),
                distributedAgentProperties.getSupervisorAgentId(),
                targetAgentId,
                graphState.intent(),
                graphState.domain(),
                graphState.userMessage(),
                context,
                List.of(),
                List.of(),
                resolveExpectedOutput(graphState.domain()),
                A2aInvokeSupport.buildIdempotencyKey(graphState.taskId(), targetAgentId, graphState.intent(), retryCount),
                Boolean.TRUE.equals(request.stream())
        );
    }

    private String resolveExpectedOutput(String domain) {
        return switch (domain) {
            case "marketing" -> "Return a marketing copy draft with compact structured output";
            case "media" -> "Return a media generation task with compact structured output";
            case "trade" -> "Return a trade assessment with compact structured output";
            case "contract" -> "Return a contract summary and risk review with compact structured output";
            default -> "Return listing search summaries with compact structured output";
        };
    }
}
