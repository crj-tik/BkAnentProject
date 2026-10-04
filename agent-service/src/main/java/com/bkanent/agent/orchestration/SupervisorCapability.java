package com.bkanent.agent.orchestration;

import org.springframework.ai.tool.ToolCallback;

/** A round-owned real binding; logical identity is independent of the model alias and endpoint. */
public record SupervisorCapability(String capabilityId, String protocol, String target,
                                   String version, ToolCallback callback) {
    public String toolName() { return callback.getToolDefinition().name(); }
}
