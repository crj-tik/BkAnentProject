package com.bkanent.agent.graph.node;

import com.bkanent.agent.graph.SupervisorGraphNode;
import com.bkanent.agent.graph.SupervisorGraphState;
import com.bkanent.agent.service.SupervisorIntentPlanningService;
import com.bkanent.agent.service.SupervisorAgentRoutingService;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Component;

@Component
public class SelectAgentNode implements SupervisorGraphNode {

    private final SupervisorAgentRoutingService supervisorAgentRoutingService;
    private final SupervisorIntentPlanningService supervisorIntentPlanningService;

    public SelectAgentNode(SupervisorAgentRoutingService supervisorAgentRoutingService,
                          SupervisorIntentPlanningService supervisorIntentPlanningService) {
        this.supervisorAgentRoutingService = supervisorAgentRoutingService;
        this.supervisorIntentPlanningService = supervisorIntentPlanningService;
    }

    @Override
    public SupervisorGraphState apply(SupervisorGraphState state) {
        if (Boolean.TRUE.equals(state.requireParallel()) && state.parallelDomains() != null && state.parallelDomains().size() > 1) {
            return state.withSelectedAgent("parallel:" + String.join(",", state.parallelDomains()));
        }
        String plannedAgentId = supervisorIntentPlanningService.readSelectedAgentId(state.sharedContext());
        if (StringUtils.hasText(plannedAgentId)) {
            return state.withSelectedAgent(plannedAgentId);
        }
        String domain = state.domain() == null ? "listing" : state.domain();
        return state.withSelectedAgent(supervisorAgentRoutingService.selectAgent(
                domain,
                state.userMessage(),
                state.sharedContext()
        ).agentId());
    }
}
