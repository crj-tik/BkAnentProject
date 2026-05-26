package com.bkanent.agent.tool;

/**
 * AgentTool 工具类。
 */
public interface AgentTool {

    default boolean isBaseTool() {
        return false;
    }
}
