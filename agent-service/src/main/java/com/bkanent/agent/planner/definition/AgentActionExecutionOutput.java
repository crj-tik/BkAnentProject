package com.bkanent.agent.planner.definition;

import java.util.Map;

/**
 * Agent 动作执行输出对象。
 *
 * <p>同时承载返回文本和结构化负载，便于后续步骤继续引用字段路径。</p>
 */
public record AgentActionExecutionOutput(
        String text,
        Map<String, Object> payload
) {

    public static AgentActionExecutionOutput of(String text, Map<String, Object> payload) {
        return new AgentActionExecutionOutput(text, payload == null ? Map.of() : Map.copyOf(payload));
    }
}
