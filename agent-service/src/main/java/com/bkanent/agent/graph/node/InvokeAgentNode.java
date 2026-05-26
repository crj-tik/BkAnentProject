package com.bkanent.agent.graph.node;

import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import com.bkanent.agent.service.A2aExecutionService;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class InvokeAgentNode {

    private final A2aExecutionService a2aExecutionService;

    public InvokeAgentNode(A2aExecutionService a2aExecutionService) {
        this.a2aExecutionService = a2aExecutionService;
    }

    public AgentTaskInvokeResponse invoke(RegisteredAgentDescriptor descriptor, AgentTaskInvokeRequest request) {
        return a2aExecutionService.execute(descriptor, request, "single_agent", Map.of(
                "targetAgentId", descriptor.agentId()
        ));
    }
}
