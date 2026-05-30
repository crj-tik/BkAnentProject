package com.bkanent.contract.service.impl;

import com.bkanent.common.agent.AgentCard;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.bkanent.contract.config.ContractAgentProperties;
import com.bkanent.contract.service.ContractAgentService;
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
public class ContractAgentServiceImpl implements ContractAgentService {

    private static final Logger log = LoggerFactory.getLogger(ContractAgentServiceImpl.class);

    private final ChatClient contractChatClient;
    private final ContractAgentProperties properties;
    private final ObjectMapper objectMapper;

    public ContractAgentServiceImpl(@Qualifier("contractChatClient") ChatClient contractChatClient,
                                    ContractAgentProperties properties,
                                    ObjectMapper objectMapper) {
        this.contractChatClient = contractChatClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentCard getAgentCard() {
        return new AgentCard(
                "contract-agent",
                "Contract Agent",
                "Responsible for contract parsing, risk review, and contract lifecycle management with LLM-driven analysis",
                "2.0.0",
                List.of("contract-parse", "contract-risk-review"),
                List.of("contract"),
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
            String llmResponse = contractChatClient.prompt()
                    .system(properties.getSystemPrompt())
                    .user(userPrompt)
                    .options(chatOptions())
                    .call()
                    .content();

            Map<String, Object> parsed = parseLlmResponse(llmResponse);
            return buildResponse(request, parsed, context);

        } catch (Exception e) {
            log.error("LLM invocation failed for contract analysis, falling back to rule-based", e);
            return fallbackResponse(request, context, instruction);
        }
    }

    private String buildUserPrompt(String instruction, Map<String, Object> context) {
        StringBuilder sb = new StringBuilder();
        sb.append("Analyze the following contract request:\n\n");
        sb.append("Instruction: ").append(instruction).append("\n\n");
        if (!context.isEmpty()) {
            sb.append("Context:\n");
            context.forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append("\n"));
            sb.append("\n");
        }
        sb.append("""
                Please use available tools to gather relevant contract data and produce a structured assessment.

                You MUST respond with a JSON object in this exact format:
                {
                  "decision": "PROCEED|MANUAL_REVIEW|RISK_ALERT|REJECT",
                  "riskLevel": "low|medium|high",
                  "riskFactors": ["factor1", "factor2"],
                  "contractStatus": "DRAFT|SEALED|ARCHIVED|UNKNOWN",
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
            log.warn("Failed to parse LLM response as JSON, using raw text");
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
                                                   Map<String, Object> parsed,
                                                   Map<String, Object> context) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("resultType", "contract_review");
        output.put("decision", parsed.getOrDefault("decision", "MANUAL_REVIEW"));
        output.put("riskLevel", parsed.getOrDefault("riskLevel", "medium"));
        output.put("riskFactors", parsed.getOrDefault("riskFactors", List.of()));
        output.put("contractStatus", parsed.getOrDefault("contractStatus", "UNKNOWN"));
        output.put("recommendedActions", parsed.getOrDefault("recommendedActions", List.of()));
        output.put("summary", parsed.getOrDefault("summary", ""));
        output.put("contentType", "contract_review");

        String decision = String.valueOf(output.get("decision"));
        List<String> nextHints = deriveNextHints(decision);

        return new AgentTaskInvokeResponse(
                request.sessionId(),
                request.taskId(),
                "contract-agent",
                "COMPLETED",
                output,
                List.of(),
                nextHints,
                "Contract review: " + decision + " - " + output.get("summary"),
                request.traceId()
        );
    }

    private List<String> deriveNextHints(String decision) {
        return switch (decision) {
            case "PROCEED" -> List.of("settlement.prepare", "trade.feasibility_analysis");
            case "RISK_ALERT" -> List.of("manager.review", "document.request");
            case "REJECT" -> List.of("notification.send", "case.close");
            default -> List.of("manual.review", "document.request");
        };
    }

    private AgentTaskInvokeResponse fallbackResponse(AgentTaskInvokeRequest request,
                                                      Map<String, Object> context,
                                                      String instruction) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("resultType", "contract_review");
        output.put("decision", "MANUAL_REVIEW");
        output.put("riskLevel", "medium");
        output.put("riskFactors", List.of("rule-based fallback - LLM unavailable"));
        output.put("contractStatus", "UNKNOWN");
        output.put("summary", "Fallback rule-based assessment (LLM unavailable)");
        output.put("contentType", "contract_review");

        return new AgentTaskInvokeResponse(
                request.sessionId(), request.taskId(), "contract-agent", "COMPLETED",
                output, List.of(), List.of("manual.review"),
                "Contract review finished (fallback) with decision MANUAL_REVIEW",
                request.traceId()
        );
    }

    private AgentTaskInvokeResponse emptyResponse(AgentTaskInvokeRequest request, String reason) {
        return new AgentTaskInvokeResponse(
                request.sessionId(), request.taskId(), "contract-agent", "COMPLETED",
                Map.of("decision", "MANUAL_REVIEW", "summary", reason),
                List.of(), List.of(), reason, request.traceId()
        );
    }
}