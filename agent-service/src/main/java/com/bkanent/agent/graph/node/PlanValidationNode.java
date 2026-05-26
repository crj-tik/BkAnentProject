package com.bkanent.agent.graph.node;

import com.bkanent.agent.graph.SupervisorGraphNode;
import com.bkanent.agent.graph.SupervisorGraphState;
import com.bkanent.agent.model.distributed.WorkflowPlan;
import com.bkanent.agent.service.SupervisorIntentPlanningService;
import com.bkanent.agent.service.WorkflowPlanValidator;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class PlanValidationNode implements SupervisorGraphNode {

    private final SupervisorIntentPlanningService supervisorIntentPlanningService;
    private final WorkflowPlanValidator workflowPlanValidator;

    public PlanValidationNode(SupervisorIntentPlanningService supervisorIntentPlanningService,
                              WorkflowPlanValidator workflowPlanValidator) {
        this.supervisorIntentPlanningService = supervisorIntentPlanningService;
        this.workflowPlanValidator = workflowPlanValidator;
    }

    @Override
    public SupervisorGraphState apply(SupervisorGraphState state) {
        WorkflowPlan workflowPlan = supervisorIntentPlanningService.readPlan(state.sharedContext());
        if (workflowPlan == null) {
            return state;
        }
        WorkflowPlan validated = workflowPlanValidator.validate(workflowPlan);
        Map<String, Object> nextContext = supervisorIntentPlanningService.enrichContext(
                state.sharedContext(),
                validated
        );
        return state.withSharedContext(nextContext);
    }
}
