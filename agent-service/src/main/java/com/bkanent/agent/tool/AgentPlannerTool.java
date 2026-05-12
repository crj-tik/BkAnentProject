package com.bkanent.agent.tool;

/**
 * Agent Planner 工具标记接口。
 *
 * <p>实现类可以通过覆盖 {@link #isBaseTool()}，或配合类级注解来声明自己是否属于
 * ChatClient 启动时默认注入的基础工具。</p>
 */
public interface AgentPlannerTool {

    /**
     * 是否属于基础工具。
     *
     * @return 返回 {@code true} 表示该工具会被注册为 ChatClient 的默认工具
     */
    default boolean isBaseTool() {
        return false;
    }
}
