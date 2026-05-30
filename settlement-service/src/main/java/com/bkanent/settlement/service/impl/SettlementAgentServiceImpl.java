package com.bkanent.settlement.service.impl;

import com.bkanent.common.agent.AgentCard;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.bkanent.settlement.config.SettlementAgentProperties;
import com.bkanent.settlement.service.SettlementAgentService;
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
public class SettlementAgentServiceImpl implements SettlementAgentService {

    private static final Logger log = LoggerFactory.getLogger(SettlementAgentServiceImpl.class);

    private final ChatClient settlementChatClient;
    private final SettlementAgentProperties properties;
    private final ObjectMapper objectMapper;

    public SettlementAgentServiceImpl(@Qualifier("settlementChatClient") ChatClient settlementChatClient,
                                      SettlementAgentProperties properties,
                                      ObjectMapper objectMapper) {
        this.settlementChatClient = settlementChatClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentCard getAgentCard() {
        return new AgentCard(
                "settlement-agent",
                "Settlement Agent",
                "Responsible for settlement calculation, commission computation, payout batch preparation, and monthly summary analysis with LLM-driven reasoning",
                "2.0.0",
                List.of("settlement-calculate", "settlement-payout-prepare"),
                List.of("settlement"),
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
            String llmResponse = settlementChatClient.prompt()
                    .system(properties.getSystemPrompt())
                    .user(userPrompt)
                    .options(chatOptions())
                    .call()
                    .content();

            Map<String, Object> parsed = parseLlmResponse(llmResponse);
            return buildResponse(request, parsed);

        } catch (Exception e) {
            log.error("LLM invocation failed for settlement analysis, falling back to rule-based", e);
            return fallbackResponse(request, context, instruction);
        }
    }

    private String buildUserPrompt(String instruction, Map<String, Object> context) {
        StringBuilder sb = new StringBuilder();
        sb.append("Analyze the following settlement request:\n\n");
        sb.append("Instruction: ").append(instruction).append("\n\n");
        if (!context.isEmpty()) {
            sb.append("Context:\n");
            context.forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append("\n"));
            sb.append("\n");
        }
        sb.append("""
                Please use available tools to gather relevant settlement data (settlement details, commission calculations,
                monthly summaries, payout batches) and produce a structured assessment.

                You MUST respond with a JSON object in this exact format:
                {
                  "decision": "PROCEED|MANUAL_REVIEW|HOLD",
                  "payoutStatus": "PREPARED|BATCHED|PAID|PENDING",
                  "commissionAmount": 0.0,
                  "monthlySummaryAvailable": true,
                  "recommendedActions": ["action1", "action2"],
                  "summary": "A concise paragraph explaining the reasoning."
                }
                """);
        return sb.toString();
    }

    private Map<String, Object> parseLlmResponse(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return Map.of("decision", "MANUAL_REVIEW", "payoutStatus", "PENDING",
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
            log.warn("Failed to parse LLM response as JSON, using raw text");
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("decision", "MANUAL_REVIEW");
            fallback.put("payoutStatus", "PENDING");
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
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("resultType", "settlement_prepare");
        output.put("decision", parsed.getOrDefault("decision", "MANUAL_REVIEW"));
        output.put("payoutStatus", parsed.getOrDefault("payoutStatus", "PENDING"));
        output.put("commissionAmount", parsed.getOrDefault("commissionAmount", 0.0));
        output.put("monthlySummaryAvailable", parsed.getOrDefault("monthlySummaryAvailable", false));
        output.put("recommendedActions", parsed.getOrDefault("recommendedActions", List.of()));
        output.put("summary", parsed.getOrDefault("summary", ""));
        output.put("contentType", "settlement_summary");

        String decision = String.valueOf(output.get("decision"));
        List<String> nextHints = deriveNextHints(decision);

        return new AgentTaskInvokeResponse(
                request.sessionId(),
                request.taskId(),
                "settlement-agent",
                "COMPLETED",
                output,
                List.of(),
                nextHints,
                "Settlement: " + decision + " - " + output.get("summary"),
                request.traceId()
        );
    }

    private List<String> deriveNextHints(String decision) {
        return switch (decision) {
            case "PROCEED" -> List.of("settlement.batch", "notification.send");
            case "HOLD" -> List.of("manager.review", "document.request");
            default -> List.of("manual.review", "settlement.review");
        };
    }

    private AgentTaskInvokeResponse fallbackResponse(AgentTaskInvokeRequest request,
                                                      Map<String, Object> context,
                                                      String instruction) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("resultType", "settlement_prepare");
        output.put("decision", "MANUAL_REVIEW");
        output.put("payoutStatus", "PENDING");
        output.put("summary", "Fallback rule-based assessment (LLM unavailable)");
        output.put("contentType", "settlement_summary");

        return new AgentTaskInvokeResponse(
                request.sessionId(), request.taskId(), "settlement-agent", "COMPLETED",
                output, List.of(), List.of("settlement.review"),
                "Settlement finished (fallback) with decision MANUAL_REVIEW",
                request.traceId()
        );
    }

    private AgentTaskInvokeResponse emptyResponse(AgentTaskInvokeRequest request, String reason) {
        return new AgentTaskInvokeResponse(
                request.sessionId(), request.taskId(), "settlement-agent", "COMPLETED",
                Map.of("decision", "MANUAL_REVIEW", "summary", reason),
                List.of(), List.of(), reason, request.traceId()
        );
    }
}