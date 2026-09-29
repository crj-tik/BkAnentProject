package com.bkanent.marketing.service.impl;

import com.bkanent.common.agent.AgentCard;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.bkanent.marketing.config.MarketingAgentProperties;
import com.bkanent.marketing.service.MarketingAgentService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.DefaultChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MarketingAgentServiceImpl implements MarketingAgentService {

    private static final Logger log = LoggerFactory.getLogger(MarketingAgentServiceImpl.class);

    private static final String COPY_GENERATION_SYSTEM_PROMPT = """
            你是资深房产营销文案专家。依据给定的房源信息与目标平台，撰写一条可直接发布的营销文案。
            要求：贴合目标平台的内容风格与篇幅习惯；突出房源核心卖点；不得编造房源信息中不存在的
            事实（面积、价格、学区、交通等以输入为准）；只输出文案正文本身，不要附加解释或标题。
            """;

    private final ChatClient marketingChatClient;
    private final ChatModel chatModel;
    private final MarketingAgentProperties properties;
    private final ObjectMapper objectMapper;

    public MarketingAgentServiceImpl(@Qualifier("marketingChatClient") ChatClient marketingChatClient,
                                      ChatModel chatModel,
                                      MarketingAgentProperties properties,
                                      ObjectMapper objectMapper) {
        this.marketingChatClient = marketingChatClient;
        this.chatModel = chatModel;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String generateCopy(String listingSummary, String platform) {
        if (listingSummary == null || listingSummary.isBlank()) {
            throw new IllegalArgumentException("listing summary must not be blank");
        }
        String userPrompt = "目标平台: " + (platform == null || platform.isBlank() ? "通用" : platform)
                + "\n\n房源信息:\n" + listingSummary
                + "\n\n请为该房源撰写营销文案正文。";
        Prompt prompt = new Prompt(
                List.of(new SystemMessage(COPY_GENERATION_SYSTEM_PROMPT), new UserMessage(userPrompt)),
                copyChatOptions());
        String response = chatModel.call(prompt)
                .getResult().getOutput().getText();
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("LLM returned empty marketing copy");
        }
        return response.strip();
    }

    private ChatOptions copyChatOptions() {
        DefaultChatOptions options = new DefaultChatOptions();
        options.setModel(properties.getModel());
        options.setTemperature(properties.getTemperature());
        options.setMaxTokens(properties.getMaxTokens());
        return options;
    }

    @Override
    public AgentCard getAgentCard() {
        return new AgentCard(
                "marketing-agent",
                "Marketing Agent",
                "Responsible for marketing copy generation, content creation, publish preparation and execution with LLM-driven creativity",
                "2.0.0",
                List.of("marketing-copy", "marketing-adaptation", "marketing-publish"),
                List.of("marketing"),
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
            String llmResponse = marketingChatClient.prompt()
                    .system(properties.getSystemPrompt())
                    .user(userPrompt)
                    .options(chatOptions())
                    .call()
                    .content();

            Map<String, Object> parsed = parseLlmResponse(llmResponse);
            return buildResponse(request, parsed);

        } catch (Exception e) {
            log.error("LLM invocation failed for marketing, falling back to rule-based", e);
            return fallbackResponse(request, context, instruction);
        }
    }

    private String buildUserPrompt(String instruction, Map<String, Object> context) {
        StringBuilder sb = new StringBuilder();
        sb.append("Handle the following marketing request:\n\n");
        sb.append("Instruction: ").append(instruction).append("\n\n");
        if (!context.isEmpty()) {
            sb.append("Context:\n");
            context.forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append("\n"));
            sb.append("\n");
        }
        sb.append("""
                Please use available tools to create marketing content or publish existing content, then produce a structured result.

                You MUST respond with a JSON object in this exact format:
                {
                  "intent": "generate_copy|publish_prepare|publish",
                  "decision": "SUCCESS|FAILED|QUEUED",
                  "contentId": 0,
                  "platform": "DOUYIN|WECHAT|XIAOHONGSHU",
                  "publishStatus": "DRAFT|PUBLISHED|FAILED",
                  "draftText": "generated copy text here",
                  "summary": "A concise paragraph describing what was done."
                }
                """);
        return sb.toString();
    }

    private Map<String, Object> parseLlmResponse(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return Map.of("decision", "FAILED", "summary", "LLM returned empty response");
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
        output.put("resultType", "marketing_result");
        output.put("intent", parsed.getOrDefault("intent", "generate_copy"));
        output.put("decision", parsed.getOrDefault("decision", "SUCCESS"));
        output.put("contentId", parsed.getOrDefault("contentId", 0));
        output.put("platform", parsed.getOrDefault("platform", "DOUYIN"));
        output.put("publishStatus", parsed.getOrDefault("publishStatus", "DRAFT"));
        output.put("draftText", parsed.getOrDefault("draftText", ""));
        output.put("summary", parsed.getOrDefault("summary", ""));
        output.put("contentType", "marketing_result");

        String intent = String.valueOf(output.get("intent"));
        List<String> nextHints = deriveNextHints(intent);

        return new AgentTaskInvokeResponse(
                request.sessionId(),
                request.taskId(),
                "marketing-agent",
                "COMPLETED",
                output,
                List.of(),
                nextHints,
                String.valueOf(output.get("summary")),
                request.traceId()
        );
    }

    private List<String> deriveNextHints(String intent) {
        return switch (intent) {
            case "generate_copy" -> List.of("marketing.publish_prepare", "marketing.adapt_content");
            case "publish_prepare" -> List.of("marketing.publish", "notification.send");
            case "publish" -> List.of("notification.send");
            default -> List.of("marketing.generate_copy");
        };
    }

    private AgentTaskInvokeResponse fallbackResponse(AgentTaskInvokeRequest request,
                                                      Map<String, Object> context,
                                                      String instruction) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("resultType", "marketing_result");
        output.put("decision", "QUEUED");
        output.put("draftText", "Fallback marketing copy (LLM unavailable): " + instruction);
        output.put("summary", "Fallback rule-based marketing (LLM unavailable)");
        output.put("contentType", "marketing_result");

        return new AgentTaskInvokeResponse(
                request.sessionId(), request.taskId(), "marketing-agent", "COMPLETED",
                output, List.of(), List.of("marketing.generate_copy"),
                "Marketing finished (fallback) with status QUEUED",
                request.traceId()
        );
    }

    private AgentTaskInvokeResponse emptyResponse(AgentTaskInvokeRequest request, String reason) {
        return new AgentTaskInvokeResponse(
                request.sessionId(), request.taskId(), "marketing-agent", "COMPLETED",
                Map.of("decision", "FAILED", "summary", reason),
                List.of(), List.of(), reason, request.traceId()
        );
    }
}