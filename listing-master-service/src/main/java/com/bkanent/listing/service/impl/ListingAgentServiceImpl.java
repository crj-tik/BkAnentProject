package com.bkanent.listing.service.impl;

import com.bkanent.common.agent.AgentCard;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.bkanent.listing.config.ListingAgentProperties;
import com.bkanent.listing.service.ListingAgentService;
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
public class ListingAgentServiceImpl implements ListingAgentService {

    private static final Logger log = LoggerFactory.getLogger(ListingAgentServiceImpl.class);

    private final ChatClient listingChatClient;
    private final ListingAgentProperties properties;
    private final ObjectMapper objectMapper;

    public ListingAgentServiceImpl(@Qualifier("listingChatClient") ChatClient listingChatClient,
                                    ListingAgentProperties properties,
                                    ObjectMapper objectMapper) {
        this.listingChatClient = listingChatClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentCard getAgentCard() {
        return new AgentCard(
                "listing-agent",
                "Listing Agent",
                "Responsible for property listing search, recommendation, and summary with LLM-driven insights",
                "2.0.0",
                List.of("listing-search", "listing-recommendation"),
                List.of("listing"),
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
            String llmResponse = listingChatClient.prompt()
                    .system(properties.getSystemPrompt())
                    .user(userPrompt)
                    .options(chatOptions())
                    .call()
                    .content();

            Map<String, Object> parsed = parseLlmResponse(llmResponse);
            return buildResponse(request, parsed);

        } catch (Exception e) {
            log.error("LLM invocation failed for listing search, falling back to rule-based", e);
            return fallbackResponse(request, context, instruction);
        }
    }

    private String buildUserPrompt(String instruction, Map<String, Object> context) {
        StringBuilder sb = new StringBuilder();
        sb.append("Handle the following listing search request:\n\n");
        sb.append("Instruction: ").append(instruction).append("\n\n");
        if (!context.isEmpty()) {
            sb.append("Context:\n");
            context.forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append("\n"));
            sb.append("\n");
        }
        sb.append("""
                Please use available tools to search and retrieve property listing data, then produce a structured result.

                You MUST respond with a JSON object in this exact format:
                {
                  "decision": "SUCCESS|NO_RESULTS|FAILED",
                  "listingCount": 0,
                  "keyword": "the-search-keyword",
                  "topPick": "listing-id-or-empty",
                  "recommendedActions": ["action1", "action2"],
                  "summary": "A concise paragraph summarizing the search results."
                }
                """);
        return sb.toString();
    }

    private Map<String, Object> parseLlmResponse(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return Map.of("decision", "FAILED",
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
            fallback.put("decision", "FAILED");
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
        output.put("resultType", "listing_search");
        output.put("decision", parsed.getOrDefault("decision", "SUCCESS"));
        output.put("listingCount", parsed.getOrDefault("listingCount", 0));
        output.put("keyword", parsed.getOrDefault("keyword", ""));
        output.put("topPick", parsed.getOrDefault("topPick", ""));
        output.put("recommendedActions", parsed.getOrDefault("recommendedActions", List.of()));
        output.put("summary", parsed.getOrDefault("summary", ""));
        output.put("contentType", "listing_search");

        List<String> nextHints = "SUCCESS".equals(String.valueOf(output.get("decision")))
                ? List.of("compare.listings", "marketing.generate_copy", "trade.feasibility_analysis")
                : List.of("listing.search");

        return new AgentTaskInvokeResponse(
                request.sessionId(),
                request.taskId(),
                "listing-agent",
                "COMPLETED",
                output,
                List.of(),
                nextHints,
                String.valueOf(output.get("summary")),
                request.traceId()
        );
    }

    private AgentTaskInvokeResponse fallbackResponse(AgentTaskInvokeRequest request,
                                                      Map<String, Object> context,
                                                      String instruction) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("resultType", "listing_search");
        output.put("decision", "PARTIAL");
        output.put("summary", "Fallback rule-based listing search (LLM unavailable)");
        output.put("contentType", "listing_search");

        return new AgentTaskInvokeResponse(
                request.sessionId(), request.taskId(), "listing-agent", "COMPLETED",
                output, List.of(), List.of("listing.search"),
                "Listing search finished (fallback) with status PARTIAL",
                request.traceId()
        );
    }

    private AgentTaskInvokeResponse emptyResponse(AgentTaskInvokeRequest request, String reason) {
        return new AgentTaskInvokeResponse(
                request.sessionId(), request.taskId(), "listing-agent", "COMPLETED",
                Map.of("decision", "FAILED", "summary", reason),
                List.of(), List.of(), reason, request.traceId()
        );
    }
}
