package com.bkanent.agent.service;

import com.bkanent.agent.config.AgentDeepSeekProperties;
import com.bkanent.agent.enums.AgentExecutionMode;
import com.bkanent.agent.model.chat.AgentChatRequest;
import com.bkanent.agent.model.chat.AgentChatResponse;
import com.bkanent.agent.model.chat.AgentToolDecision;
import com.bkanent.agent.model.planner.AgentPlannerSession;
import com.bkanent.agent.tool.context.AgentToolContextHolder;
import com.bkanent.agent.tool.context.AgentToolSessionSnapshot;
import org.springframework.stereotype.Service;

/**
 * Main agent orchestration service.
 */
@Service
public class AgentOrchestratorService {

    private final DeepSeekChatService deepSeekChatService;
    private final AgentDeepSeekProperties agentDeepSeekProperties;
    private final AgentPlanExecutorService agentPlanExecutorService;
    private final AgentPlannerService agentPlannerService;
    private final AgentPlannerLogPersistenceService agentPlannerLogPersistenceService;

    public AgentOrchestratorService(DeepSeekChatService deepSeekChatService,
                                    AgentDeepSeekProperties agentDeepSeekProperties,
                                    AgentPlanExecutorService agentPlanExecutorService,
                                    AgentPlannerService agentPlannerService,
                                    AgentPlannerLogPersistenceService agentPlannerLogPersistenceService) {
        this.deepSeekChatService = deepSeekChatService;
        this.agentDeepSeekProperties = agentDeepSeekProperties;
        this.agentPlanExecutorService = agentPlanExecutorService;
        this.agentPlannerService = agentPlannerService;
        this.agentPlannerLogPersistenceService = agentPlannerLogPersistenceService;
    }

    public AgentChatResponse chat(AgentChatRequest request) {
        String message = request.message() == null ? "" : request.message().trim();
        if (message.isBlank()) {
            throw new IllegalArgumentException("Message must not be blank");
        }

        int topK = request.topK() == null ? agentDeepSeekProperties.getDefaultTopK() : Math.max(1, request.topK());
        boolean allowMcp = request.allowMcp() == null || request.allowMcp();
        AgentExecutionMode executionMode = request.executionMode() == null ? AgentExecutionMode.TOOL : request.executionMode();

        AgentToolContextHolder.init(request.collectionName(), topK, allowMcp);
        try {
            return executionMode == AgentExecutionMode.PLANNER
                    ? plannerChat(message, executionMode)
                    : toolChat(message, request.collectionName(), topK, allowMcp, executionMode);
        } finally {
            AgentToolContextHolder.clear();
        }
    }

    private AgentChatResponse toolChat(String message,
                                       String collectionName,
                                       int topK,
                                       boolean allowMcp,
                                       AgentExecutionMode executionMode) {
        String answer = deepSeekChatService.call(
                deepSeekChatService.getSystemPrompt(),
                buildUserPrompt(message, collectionName, topK, allowMcp)
        );
        AgentToolSessionSnapshot snapshot = AgentToolContextHolder.snapshot();
        return new AgentChatResponse(
                answer,
                deepSeekChatService.getModel(),
                null,
                executionMode,
                buildDecision(snapshot),
                snapshot.milvusResults(),
                snapshot.toolContext(),
                null
        );
    }

    private AgentChatResponse plannerChat(String message, AgentExecutionMode executionMode) {
        AgentPlannerSession plannerSession = agentPlanExecutorService.execute(message);
        String answer = agentPlannerService.buildFinalAnswer(message, plannerSession.executionResults(), plannerSession.completed());
        AgentToolSessionSnapshot snapshot = AgentToolContextHolder.snapshot();
        agentPlannerLogPersistenceService.savePlannerSession(
                plannerSession.sessionNo(),
                executionMode,
                message,
                answer,
                snapshot.toolContext(),
                plannerSession
        );
        return new AgentChatResponse(
                answer,
                deepSeekChatService.getModel(),
                plannerSession.sessionNo(),
                executionMode,
                buildDecision(snapshot),
                snapshot.milvusResults(),
                snapshot.toolContext(),
                plannerSession
        );
    }

    private String buildUserPrompt(String message, String collectionName, int topK, boolean allowMcp) {
        return """
                User question:
                %s

                Current session context:
                - Knowledge collection: %s
                - Default search topK: %s
                - MCP tools allowed: %s

                Decide whether tools are needed first, then answer based on real tool results.
                """.formatted(
                message,
                collectionName == null || collectionName.isBlank() ? "agent_knowledge" : collectionName,
                topK,
                allowMcp ? "yes" : "no"
        ).trim();
    }

    private AgentToolDecision buildDecision(AgentToolSessionSnapshot snapshot) {
        if (!snapshot.usedTool()) {
            return new AgentToolDecision(false, null, null, null, "Model decided no tool call was needed");
        }
        boolean usedKnowledgeTool = snapshot.milvusResults() != null && !snapshot.milvusResults().isEmpty();
        String reason = usedKnowledgeTool
                ? "Model invoked the Milvus knowledge retrieval tool"
                : "Model invoked an external business tool";
        return new AgentToolDecision(usedKnowledgeTool, snapshot.firstToolName(), snapshot.firstToolQuery(), snapshot.topK(), reason);
    }
}
