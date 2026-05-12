package com.bkanent.agent.planner.context;

import com.bkanent.agent.model.planner.AgentPlanStep;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Planner 步骤执行上下文。
 *
 * <p>封装当前步骤、已解析参数和上游传入文本，供执行器和动作注册表统一消费。</p>
 */
public record AgentPlanStepContext(
        /** 当前执行步骤。 */
        AgentPlanStep step,
        /** 已解析完成的参数。 */
        Map<String, Object> resolvedArguments,
        /** 当前步骤接收到的上游输入文本。 */
        String resolvedInput
) {

    public AgentPlanStepContext {
        resolvedArguments = resolvedArguments == null ? Map.of() : Map.copyOf(resolvedArguments);
    }

    public static AgentPlanStepContext of(AgentPlanStep step, Map<String, Object> resolvedArguments, String resolvedInput) {
        return new AgentPlanStepContext(step, new LinkedHashMap<>(resolvedArguments), resolvedInput);
    }
}
