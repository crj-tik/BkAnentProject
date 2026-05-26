package com.bkanent.agent.graph.node;

import com.bkanent.agent.graph.SupervisorGraphNode;
import com.bkanent.agent.graph.SupervisorGraphState;
import com.bkanent.agent.model.distributed.WorkflowPlan;
import com.bkanent.agent.service.SupervisorIntentPlanningService;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class LlmIntentPlanNode implements SupervisorGraphNode {

    private final SupervisorIntentPlanningService supervisorIntentPlanningService;

    public LlmIntentPlanNode(SupervisorIntentPlanningService supervisorIntentPlanningService) {
        this.supervisorIntentPlanningService = supervisorIntentPlanningService;
    }

    @Override
    public SupervisorGraphState apply(SupervisorGraphState state) {
        try {
            WorkflowPlan workflowPlan = supervisorIntentPlanningService.tryPlan(
                    state.userMessage(),
                    state.sharedContext()
            );
            if (workflowPlan == null) {
                return state;
            }
            Map<String, Object> nextContext = supervisorIntentPlanningService.enrichContext(
                    state.sharedContext(),
                    workflowPlan
            );
            return state.withSharedContext(nextContext);
        } catch (Exception ignored) {
            return state;
        }
    }
}
