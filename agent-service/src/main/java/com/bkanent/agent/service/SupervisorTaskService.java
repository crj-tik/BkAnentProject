package com.bkanent.agent.service;

import com.bkanent.agent.graph.ParallelAgentSubgraph;
import com.bkanent.agent.graph.SingleAgentSubgraph;
import com.bkanent.agent.graph.SupervisorGraphPlanner;
import com.bkanent.agent.graph.SupervisorGraphState;
import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.agent.model.distributed.SupervisorTaskResponse;
import com.bkanent.agent.registry.AgentRegistry;
import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import com.bkanent.common.agent.AgentCard;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

@Service
public class SupervisorTaskService {

    private final AgentRegistry agentRegistry;
    private final SingleAgentSubgraph singleAgentSubgraph;
    private final ParallelAgentSubgraph parallelAgentSubgraph;
    private final SupervisorGraphPlanner supervisorGraphPlanner;
    private final SupervisorGovernanceService supervisorGovernanceService;

    public SupervisorTaskService(AgentRegistry agentRegistry,
                                 SingleAgentSubgraph singleAgentSubgraph,
                                 ParallelAgentSubgraph parallelAgentSubgraph,
                                 SupervisorGraphPlanner supervisorGraphPlanner,
                                 SupervisorGovernanceService supervisorGovernanceService) {
        this.agentRegistry = agentRegistry;
        this.singleAgentSubgraph = singleAgentSubgraph;
        this.parallelAgentSubgraph = parallelAgentSubgraph;
        this.supervisorGraphPlanner = supervisorGraphPlanner;
        this.supervisorGovernanceService = supervisorGovernanceService;
    }

    public SupervisorTaskResponse submitTask(SupervisorTaskRequest request) {
        String message = request.userMessage() == null ? "" : request.userMessage().trim();
        if (message.isBlank()) {
            throw new IllegalArgumentException("userMessage must not be blank");
        }

        String sessionId = StringUtils.hasText(request.sessionId()) ? request.sessionId() : UUID.randomUUID().toString();
        String taskId = StringUtils.hasText(request.requestId()) ? request.requestId() : UUID.randomUUID().toString();
        String traceId = StringUtils.hasText(request.traceId()) ? request.traceId() : taskId;
        SupervisorGraphState graphState = supervisorGraphPlanner.plan(request, sessionId, taskId, traceId);

        List<String> parallelDomains = graphState.parallelDomains();
        if (parallelDomains.size() > 1) {
            AgentTaskInvokeResponse response = parallelAgentSubgraph.execute(request, graphState).response();
            return new SupervisorTaskResponse(
                    sessionId,
                    taskId,
                    response.status(),
                    buildFinalAnswer(response),
                    response.artifactIds(),
                    traceId,
                    "parallel:" + String.join(",", parallelDomains),
                    supervisorGovernanceService.extractGovernanceMetadata(request)
            );
        }

        String domain = graphState.domain();
        RegisteredAgentDescriptor descriptor = agentRegistry.getByAgentId(graphState.selectedAgentId())
                .orElseGet(() -> selectAgent(domain, message));
        AgentTaskInvokeResponse response = singleAgentSubgraph.execute(request, graphState, descriptor).response();
        return new SupervisorTaskResponse(
                sessionId,
                taskId,
                response.status(),
                buildFinalAnswer(response),
                response.artifactIds(),
                traceId,
                descriptor.agentId(),
                supervisorGovernanceService.extractGovernanceMetadata(request)
        );
    }

    public List<AgentCard> listAgents() {
        return agentRegistry.listCards();
    }

    private RegisteredAgentDescriptor selectAgent(String domain, String message) {
        List<RegisteredAgentDescriptor> matchedAgents = agentRegistry.findByDomain(domain);
        if (!matchedAgents.isEmpty()) {
            return matchedAgents.get(0);
        }
        List<RegisteredAgentDescriptor> listingAgents = agentRegistry.findByDomain("listing");
        if (!listingAgents.isEmpty() && containsListingIntent(message)) {
            return listingAgents.get(0);
        }
        return agentRegistry.getByAgentId("listing-agent")
                .or(() -> listingAgents.stream().findFirst())
                .orElseThrow(() -> new IllegalStateException("No registered agent available"));
    }

    private String buildFinalAnswer(AgentTaskInvokeResponse response) {
        if (StringUtils.hasText(response.summary())) {
            return response.summary();
        }
        Object count = response.structuredOutput() == null ? null : response.structuredOutput().get("listingCount");
        if (count != null) {
            return "Found " + count + " listing candidates.";
        }
        return "Child agent finished processing.";
    }

    private boolean containsListingIntent(String message) {
        return message.contains("房源")
                || message.contains("找房")
                || message.contains("小区")
                || message.contains("listing")
                || message.contains("房子");
    }
}
