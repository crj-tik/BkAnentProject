package com.bkanent.media.service.impl;

import com.bkanent.common.agent.AgentCard;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.bkanent.media.config.MediaAgentProperties;
import com.bkanent.media.service.MediaAgentService;
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
public class MediaAgentServiceImpl implements MediaAgentService {

    private static final Logger log = LoggerFactory.getLogger(MediaAgentServiceImpl.class);

    private final ChatClient mediaChatClient;
    private final MediaAgentProperties properties;
    private final ObjectMapper objectMapper;

    public MediaAgentServiceImpl(@Qualifier("mediaChatClient") ChatClient mediaChatClient,
                                  MediaAgentProperties properties,
                                  ObjectMapper objectMapper) {
        this.mediaChatClient = mediaChatClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentCard getAgentCard() {
        return new AgentCard(
                "media-agent",
                "Media Agent",
                "Responsible for media task generation, video/cover asset preparation, and publish-ready media references with LLM-driven task routing",
                "2.0.0",
                List.of("media-video-task", "media-result-query", "media-cover-prepare"),
                List.of("media"),
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
            String llmResponse = mediaChatClient.prompt()
                    .system(properties.getSystemPrompt())
                    .user(userPrompt)
                    .options(chatOptions())
                    .call()
                    .content();

            Map<String, Object> parsed = parseLlmResponse(llmResponse);
            return buildResponse(request, parsed);

        } catch (Exception e) {
            log.error("LLM invocation failed for media task, falling back to rule-based", e);
            return fallbackResponse(request, context, instruction);
        }
    }

    private String buildUserPrompt(String instruction, Map<String, Object> context) {
        StringBuilder sb = new StringBuilder();
        sb.append("Handle the following media task request:\n\n");
        sb.append("Instruction: ").append(instruction).append("\n\n");
        if (!context.isEmpty()) {
            sb.append("Context:\n");
            context.forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append("\n"));
            sb.append("\n");
        }
        sb.append("""
                Please use available tools to submit media generation tasks or query task results, then produce a structured result.

                You MUST respond with a JSON object in this exact format:
                {
                  "decision": "SUBMITTED|QUERIED|FAILED",
                  "taskId": "the-media-task-id-or-empty",
                  "status": "QUEUED|PROCESSING|COMPLETED|FAILED",
                  "publishReady": true,
                  "summary": "A concise paragraph describing the media task status."
                }
                """);
        return sb.toString();
    }

    private Map<String, Object> parseLlmResponse(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return Map.of("decision", "FAILED", "status", "FAILED",
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
            fallback.put("status", "FAILED");
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
        output.put("resultType", "media_task");
        output.put("decision", parsed.getOrDefault("decision", "SUBMITTED"));
        output.put("taskId", parsed.getOrDefault("taskId", ""));
        output.put("status", parsed.getOrDefault("status", "QUEUED"));
        output.put("publishReady", parsed.getOrDefault("publishReady", true));
        output.put("summary", parsed.getOrDefault("summary", ""));
        output.put("contentType", "media_task");

        List<String> nextHints = "COMPLETED".equals(String.valueOf(output.get("status")))
                ? List.of("marketing.publish_prepare", "notification.send")
                : List.of("media.result_query");

        return new AgentTaskInvokeResponse(
                request.sessionId(),
                request.taskId(),
                "media-agent",
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
        output.put("resultType", "media_task");
        output.put("decision", "QUEUED");
        output.put("status", "QUEUED");
        output.put("publishReady", false);
        output.put("summary", "Fallback rule-based media task (LLM unavailable)");
        output.put("contentType", "media_task");

        return new AgentTaskInvokeResponse(
                request.sessionId(), request.taskId(), "media-agent", "COMPLETED",
                output, List.of(), List.of("media.result_query"),
                "Media task finished (fallback) with status QUEUED",
                request.traceId()
        );
    }

    private AgentTaskInvokeResponse emptyResponse(AgentTaskInvokeRequest request, String reason) {
        return new AgentTaskInvokeResponse(
                request.sessionId(), request.taskId(), "media-agent", "COMPLETED",
                Map.of("decision", "FAILED", "summary", reason),
                List.of(), List.of(), reason, request.traceId()
        );
    }
}