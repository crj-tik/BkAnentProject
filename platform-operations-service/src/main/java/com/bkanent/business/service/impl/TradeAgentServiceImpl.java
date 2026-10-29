package com.bkanent.business.service.impl;

import com.bkanent.business.config.TradeAgentProperties;
import com.bkanent.business.service.TradeAgentService;
import com.bkanent.common.agent.AgentCard;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.DefaultChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TradeAgentServiceImpl implements TradeAgentService {

    private static final Logger log = LoggerFactory.getLogger(TradeAgentServiceImpl.class);

    private final ChatClient tradeChatClient;
    private final TradeAgentProperties properties;
    private final ObjectMapper objectMapper;

    public TradeAgentServiceImpl(@Qualifier("tradeChatClient") ChatClient tradeChatClient,
                                  TradeAgentProperties properties,
                                  ObjectMapper objectMapper) {
        this.tradeChatClient = tradeChatClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentCard getAgentCard() {
        return new AgentCard(
                "trade-agent",
                "Trade Agent",
                "Responsible for transaction feasibility analysis and risk reasoning using LLM with business data tools",
                "2.0.0",
                List.of("trade-feasibility", "trade-risk-reasoning"),
                List.of("trade"),
                true,
                true,
                "/a2a",
                List.of("text", "json"),
                List.of("text", "json")
        );
    }

    @Override
    public AgentTaskInvokeResponse invoke(AgentTaskInvokeRequest request) {
        Map<String, Object> context = request.structuredContext() == null
                ? Map.of() : request.structuredContext();
        String instruction = request.instruction() == null ? "" : request.instruction().trim();

        if (instruction.isBlank()) {
            return emptyResponse(request, "No instruction provided");
        }

        try {
            String userPrompt = buildUserPrompt(instruction, context);
            String llmResponse = tradeChatClient.prompt()
                    .system(properties.getSystemPrompt())
                    .user(userPrompt)
                    .options(chatOptions())
                    .call()
                    .content();

            Map<String, Object> parsed = parseLlmResponse(llmResponse);
            return buildResponse(request, parsed);

        } catch (Exception e) {
            log.error("LLM invocation failed for trade analysis, falling back to rule-based", e);
            return fallbackResponse(request, context, instruction);
        }
    }

    private String buildUserPrompt(String instruction, Map<String, Object> context) {
        StringBuilder sb = new StringBuilder();
        sb.append("Analyze the following trade request:\n\n");
        sb.append("Instruction: ").append(instruction).append("\n\n");
        if (!context.isEmpty()) {
            sb.append("Context:\n");
            context.forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append("\n"));
            sb.append("\n");
        }
        sb.append("""
                Please use available tools to gather relevant business data (KPIs, listings,
                store dashboards, rankings, workloads) and produce a structured assessment.

                You MUST respond with a JSON object in this exact format:
                {
                  "decision": "PROCEED|MANUAL_REVIEW|RISK_ALERT|REJECT",
                  "riskLevel": "low|medium|high",
                  "riskFactors": ["factor1", "factor2"],
                  "supportingData": {},
                  "recommendedActions": ["action1", "action2"],
                  "summary": "A concise paragraph explaining the reasoning."
                }
                """);
        return sb.toString();
    }

    private Map<String, Object> parseLlmResponse(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return Map.of("decision", "MANUAL_REVIEW", "riskLevel", "medium",
                    "summary", "LLM returned empty response");
        }
        String json = llmResponse;
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start >= 0 && end > start) {
            json = json.substring(start, end + 1);
        }
        try {
            return objectMapper.readValue(json,
                    new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse LLM response as JSON, using raw text. Response preview: {}",
                    llmResponse.substring(0, Math.min(200, llmResponse.length())));
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("decision", "MANUAL_REVIEW");
            fallback.put("riskLevel", "medium");
            fallback.put("rawResponse", llmResponse);
            fallback.put("summary", "LLM response could not be parsed as structured JSON");
            return fallback;
        }
    }

    private ChatOptions chatOptions() {
        DefaultChatOptions options = new DefaultChatOptions();
        options.setModel(properties.getModel());
        options.setTemperature(properties.getTemperature());
        options.setMaxTokens(properties.getMaxTokens());
        return options;
    }

    private AgentTaskInvokeResponse buildResponse(AgentTaskInvokeRequest request,
                                                   Map<String, Object> parsed) {
        Map<String, Object> assessment = new LinkedHashMap<>();
        assessment.put("resultType", "trade_assessment");
        assessment.put("decision", parsed.getOrDefault("decision", "MANUAL_REVIEW"));
        assessment.put("riskLevel", parsed.getOrDefault("riskLevel", "medium"));
        assessment.put("riskFactors", parsed.getOrDefault("riskFactors", List.of()));
        assessment.put("supportingData", parsed.getOrDefault("supportingData", Map.of()));
        assessment.put("recommendedActions", parsed.getOrDefault("recommendedActions", List.of()));
        assessment.put("summary", parsed.getOrDefault("summary", ""));
        assessment.put("contentType", "trade_assessment");

        String decision = String.valueOf(assessment.get("decision"));
        List<String> nextHints = deriveNextHints(decision);

        return new AgentTaskInvokeResponse(
                request.sessionId(),
                request.taskId(),
                "trade-agent",
                "COMPLETED",
                assessment,
                List.of(),
                nextHints,
                "Trade assessment: " + decision + " - " + assessment.get("summary"),
                request.traceId()
        );
    }

    private List<String> deriveNextHints(String decision) {
        return switch (decision) {
            case "PROCEED" -> List.of("contract.review", "settlement.prepare");
            case "RISK_ALERT" -> List.of("risk.mitigation", "manager.review");
            case "REJECT" -> List.of("notification.send", "case.close");
            default -> List.of("manual.review", "document.request");
        };
    }

    private AgentTaskInvokeResponse fallbackResponse(AgentTaskInvokeRequest request,
                                                      Map<String, Object> context,
                                                      String instruction) {
        Map<String, Object> assessment = new LinkedHashMap<>();
        assessment.put("resultType", "trade_assessment");
        String decision;
        if (Boolean.TRUE.equals(context.get("needsMoreDocuments"))) {
            decision = "MANUAL_REVIEW";
        } else if (instruction.contains("风险") || instruction.contains("risk")) {
            decision = "RISK_ALERT";
        } else {
            decision = "PROCEED";
        }
        assessment.put("decision", decision);
        assessment.put("riskLevel", resolveFallbackRiskLevel(context));
        assessment.put("riskFactors", List.of("rule-based fallback - LLM unavailable"));
        assessment.put("supportingData", Map.of("source", "fallback"));
        assessment.put("summary", "Fallback rule-based assessment (LLM unavailable)");
        assessment.put("contentType", "trade_assessment");

        List<String> nextHints = deriveNextHints(decision);
        return new AgentTaskInvokeResponse(
                request.sessionId(), request.taskId(), "trade-agent", "COMPLETED",
                assessment, List.of(), nextHints,
                "Trade assessment finished (fallback) with decision " + decision,
                request.traceId()
        );
    }

    private String resolveFallbackRiskLevel(Map<String, Object> context) {
        if (Boolean.TRUE.equals(context.get("needsMoreDocuments"))) {
            return "medium";
        }
        Object listingCount = context.get("listingCount");
        if (listingCount instanceof Number n && n.intValue() == 0) {
            return "high";
        }
        return "low";
    }

    private AgentTaskInvokeResponse emptyResponse(AgentTaskInvokeRequest request, String reason) {
        return new AgentTaskInvokeResponse(
                request.sessionId(), request.taskId(), "trade-agent", "COMPLETED",
                Map.of("decision", "MANUAL_REVIEW", "summary", reason),
                List.of(), List.of(), reason, request.traceId()
        );
    }
}