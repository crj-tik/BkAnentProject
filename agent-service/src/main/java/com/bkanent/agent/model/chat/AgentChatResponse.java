package com.bkanent.agent.model.chat;

import com.bkanent.agent.enums.AgentExecutionMode;
import com.bkanent.agent.model.planner.AgentPlannerSession;
import com.bkanent.agent.model.vector.MilvusSearchResult;

import java.util.List;

/**
 * Agent chat response.
 */
public record AgentChatResponse(
        String answer,
        String model,
        String sessionNo,
        AgentExecutionMode executionMode,
        AgentToolDecision decision,
        List<MilvusSearchResult> toolResults,
        String toolContext,
        AgentPlannerSession plannerSession
) {
}
