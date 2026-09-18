package com.bkanent.agent.client;

import com.bkanent.common.agent.AgentTaskInvokeResponse;

import java.util.Map;

/**
 * Supervisor-side normalized event emitted while a child agent is streaming.
 */
public record ChildAgentStreamEvent(
        String eventType,
        String content,
        Map<String, Object> metadata,
        boolean terminal,
        AgentTaskInvokeResponse result
) {
}
