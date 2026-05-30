package com.bkanent.notification.service.impl;

import com.bkanent.common.agent.AgentCard;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.bkanent.notification.config.NotificationAgentProperties;
import com.bkanent.notification.service.NotificationAgentService;
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
public class NotificationAgentServiceImpl implements NotificationAgentService {

    private static final Logger log = LoggerFactory.getLogger(NotificationAgentServiceImpl.class);

    private final ChatClient notificationChatClient;
    private final NotificationAgentProperties properties;
    private final ObjectMapper objectMapper;

    public NotificationAgentServiceImpl(@Qualifier("notificationChatClient") ChatClient notificationChatClient,
                                        NotificationAgentProperties properties,
                                        ObjectMapper objectMapper) {
        this.notificationChatClient = notificationChatClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentCard getAgentCard() {
        return new AgentCard(
                "notification-agent",
                "Notification Agent",
                "Responsible for in-app station messages, email notifications, and message management with LLM-driven routing",
                "2.0.0",
                List.of("notification-send", "notification-station"),
                List.of("notification"),
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
            String llmResponse = notificationChatClient.prompt()
                    .system(properties.getSystemPrompt())
                    .user(userPrompt)
                    .options(chatOptions())
                    .call()
                    .content();

            Map<String, Object> parsed = parseLlmResponse(llmResponse);
            return buildResponse(request, parsed);

        } catch (Exception e) {
            log.error("LLM invocation failed for notification, falling back to rule-based", e);
            return fallbackResponse(request, context, instruction);
        }
    }

    private String buildUserPrompt(String instruction, Map<String, Object> context) {
        StringBuilder sb = new StringBuilder();
        sb.append("Handle the following notification request:\n\n");
        sb.append("Instruction: ").append(instruction).append("\n\n");
        if (!context.isEmpty()) {
            sb.append("Context:\n");
            context.forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append("\n"));
            sb.append("\n");
        }
        sb.append("""
                Please use available tools to send notifications and produce a structured result.

                You MUST respond with a JSON object in this exact format:
                {
                  "decision": "SENT|FAILED|QUEUED",
                  "channel": "station|email",
                  "messageId": 0,
                  "deliveryStatus": "SENT|FAILED|PENDING",
                  "summary": "A concise paragraph describing what was sent and to whom."
                }
                """);
        return sb.toString();
    }

    private Map<String, Object> parseLlmResponse(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return Map.of("decision", "FAILED", "channel", "station",
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
            fallback.put("channel", "station");
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
        output.put("resultType", "notification_result");
        output.put("decision", parsed.getOrDefault("decision", "SENT"));
        output.put("channel", parsed.getOrDefault("channel", "station"));
        output.put("messageId", parsed.getOrDefault("messageId", 0));
        output.put("deliveryStatus", parsed.getOrDefault("deliveryStatus", "SENT"));
        output.put("summary", parsed.getOrDefault("summary", ""));
        output.put("contentType", "notification_summary");

        return new AgentTaskInvokeResponse(
                request.sessionId(),
                request.taskId(),
                "notification-agent",
                "COMPLETED",
                output,
                List.of(),
                List.of(),
                String.valueOf(output.get("summary")),
                request.traceId()
        );
    }

    private AgentTaskInvokeResponse fallbackResponse(AgentTaskInvokeRequest request,
                                                      Map<String, Object> context,
                                                      String instruction) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("resultType", "notification_result");
        output.put("decision", "QUEUED");
        output.put("channel", "station");
        output.put("deliveryStatus", "PENDING");
        output.put("summary", "Fallback rule-based notification (LLM unavailable)");
        output.put("contentType", "notification_summary");

        return new AgentTaskInvokeResponse(
                request.sessionId(), request.taskId(), "notification-agent", "COMPLETED",
                output, List.of(), List.of(),
                "Notification finished (fallback) with status QUEUED",
                request.traceId()
        );
    }

    private AgentTaskInvokeResponse emptyResponse(AgentTaskInvokeRequest request, String reason) {
        return new AgentTaskInvokeResponse(
                request.sessionId(), request.taskId(), "notification-agent", "COMPLETED",
                Map.of("decision", "FAILED", "summary", reason),
                List.of(), List.of(), reason, request.traceId()
        );
    }
}