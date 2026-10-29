package com.bkanent.knowledge.a2a;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Accepts only reference-shaped input before the Memory hook observes the conversation. */
public final class KnowledgeInputGuardHook extends AgentHook {
    private static final Set<String> ALLOWED = Set.of("operation", "knowledgeId", "requestId", "expectedVersion",
            "sourceAssetIds", "rulesVersion", "taskId");
    private final ObjectMapper mapper;

    public KnowledgeInputGuardHook(ObjectMapper mapper) { this.mapper = mapper; }
    @Override public String getName() { return "knowledge-memory-input-guard"; }
    @Override public HookPosition[] getHookPositions() { return new HookPosition[]{HookPosition.BEFORE_AGENT}; }

    @Override
    public CompletableFuture<java.util.Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
        try {
            Object raw = state == null ? null : state.value("messages").orElse(List.of());
            if (!(raw instanceof List<?> messages)) throw rejected();
            for (Object item : messages) {
                if (!(item instanceof Message message)) continue;
                if (message instanceof UserMessage user) validateUserText(user.getText());
            }
            return CompletableFuture.completedFuture(java.util.Map.of());
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(rejected());
        }
    }

    void validateUserText(String text) {
        try {
            String structured = text == null ? "" : text.trim();
            String marker = "[Structured A2A input]";
            if (structured.startsWith(marker)) structured = structured.substring(marker.length()).trim();
            JsonNode root = mapper.readTree(structured);
            if (root == null || !root.isObject()) throw rejected();
            root.fieldNames().forEachRemaining(name -> { if (!ALLOWED.contains(name)) throw rejected(); });
            String operation = requiredText(root, "operation");
            String knowledgeId = requiredText(root, "knowledgeId");
            if (!knowledgeId.matches("[0-9a-fA-F-]{36}")) throw rejected();
            if ("GENERATE".equals(operation)) {
                String requestId = requiredText(root, "requestId");
                String rules = requiredText(root, "rulesVersion");
                JsonNode version = root.get("expectedVersion");
                JsonNode ids = root.get("sourceAssetIds");
                if (!requestId.matches("[A-Za-z0-9._:-]{1,128}") || !rules.matches("[A-Za-z0-9._-]{1,64}")
                        || version == null || !version.canConvertToLong() || version.longValue() < 0
                        || ids == null || !ids.isArray() || ids.isEmpty() || ids.size() > 20) throw rejected();
                for (JsonNode id : ids) if (!id.isTextual() || !id.asText().matches("[0-9]{1,20}")) throw rejected();
                if (root.has("taskId")) throw rejected();
                return;
            }
            if ("STATUS".equals(operation)) {
                String taskId = requiredText(root, "taskId");
                if (!taskId.matches("[0-9a-fA-F-]{36}") || root.has("sourceAssetIds") || root.has("expectedVersion")
                        || root.has("requestId") || root.has("rulesVersion")) throw rejected();
                return;
            }
            throw rejected();
        } catch (RagException failure) {
            throw failure;
        } catch (Exception failure) {
            throw rejected();
        }
    }

    private static String requiredText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw rejected();
        return value.asText();
    }

    private static RagException rejected() { return new RagException(RagErrorCode.INVALID_ARGUMENT); }
}
