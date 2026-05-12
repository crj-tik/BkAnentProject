package com.bkanent.agent.planner.definition;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Planner action execution binding.
 */
public record AgentPlanActionMethodDefinition(
        AgentPlanActionDefinition definition,
        Object bean,
        Method method,
        List<String> argumentNames
) {
}
