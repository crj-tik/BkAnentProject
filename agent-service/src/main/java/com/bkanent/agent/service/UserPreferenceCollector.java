package com.bkanent.agent.service;

import com.bkanent.agent.memory.MemoryStoreClient;
import com.bkanent.agent.milvus.memory.AgentMemoryMilvusService;
import com.bkanent.common.agent.UserPreferenceRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class UserPreferenceCollector {

    private static final Logger log = LoggerFactory.getLogger(UserPreferenceCollector.class);

    private static final String EXTRACTION_PROMPT = """
            You are a user preference analyst for a real-estate platform.
            Based on the session summary below, extract any user preferences or habits you observe.

            Rules:
            - Only extract preferences supported by clear evidence in the session.
            - Assign a confidence score between 0.0 and 1.0. Only include preferences with confidence >= 0.7.
            - The preference_key should be a short snake_case identifier (e.g. "preferred_district", "budget_tier").
            - The preference_value should be a concise, human-readable value.
            - The category should be one of: location, budget, layout, style, workflow, communication, other.
            - Provide brief evidence explaining why you believe this preference exists.

            Return ONLY a JSON array, no other text:
            [{"key":"...","value":"...","category":"...","confidence":0.9,"evidence":"..."}]
            """;

    private final ChatModel chatModel;
    private final MemoryStoreClient memoryStoreClient;
    private final AgentMemoryMilvusService agentMemoryMilvusService;
    private final ObjectMapper objectMapper;

    public UserPreferenceCollector(ChatModel chatModel,
                                   MemoryStoreClient memoryStoreClient,
                                   AgentMemoryMilvusService agentMemoryMilvusService,
                                   ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.memoryStoreClient = memoryStoreClient;
        this.agentMemoryMilvusService = agentMemoryMilvusService;
        this.objectMapper = objectMapper;
    }

    @Async
    public void collectAsync(String userId, String sessionId, String userMessage,
                             Map<String, Object> sharedContext, String summary) {
        try {
            String contextDesc = buildContextDescription(userMessage, sharedContext, summary);
            List<ExtractedPreference> prefs = extractPreferences(contextDesc);
            if (prefs.isEmpty()) {
                return;
            }
            for (ExtractedPreference pref : prefs) {
                UserPreferenceRecord record = new UserPreferenceRecord(
                        userId, pref.key(), pref.value(), pref.category(),
                        BigDecimal.valueOf(pref.confidence()), pref.evidence(),
                        sessionId, LocalDateTime.now(), 1
                );
                memoryStoreClient.upsertUserPreference(record);

                try {
                    String embedText = "[" + pref.category() + "] " + pref.key() + ": " + pref.value();
                    agentMemoryMilvusService.upsertMemory(
                            "user_preference_knowledge",
                            userId + "_" + pref.key(),
                            embedText,
                            Map.of(
                                    "user_id", userId,
                                    "preference_key", pref.key(),
                                    "preference_value", pref.value(),
                                    "category", pref.category()
                            )
                    );
                } catch (Exception e) {
                    log.debug("Failed to upsert preference '{}' to Milvus: {}", pref.key(), e.getMessage());
                }
            }

            memoryStoreClient.decayUserPreferences(userId, null);
            log.info("Collected {} user preferences for user {} in session {}",
                    prefs.size(), userId, sessionId);
        } catch (Exception e) {
            log.warn("User preference collection failed for session {}: {}", sessionId, e.getMessage());
        }
    }

    private String buildContextDescription(String userMessage, Map<String, Object> sharedContext, String summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("User message: ").append(userMessage != null ? userMessage : "N/A").append("\n");
        if (summary != null) {
            sb.append("Session summary: ").append(summary).append("\n");
        }
        if (sharedContext != null && !sharedContext.isEmpty()) {
            sb.append("Session context: ");
            sharedContext.forEach((k, v) -> {
                if (!"systemConstraints".equals(k) && !"userPreferences".equals(k)) {
                    sb.append(k).append("=").append(v).append(", ");
                }
            });
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private List<ExtractedPreference> extractPreferences(String contextDesc) {
        try {
            var response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(EXTRACTION_PROMPT),
                    new UserMessage(contextDesc)
            )));
            String content = response.getResult().getOutput().getText();
            if (content == null || content.isBlank()) {
                return List.of();
            }
            String json = content.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("```\\w*\\n?", "").replaceAll("```", "").trim();
            }
            List<Map<String, Object>> raw = objectMapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});
            return raw.stream()
                    .map(m -> new ExtractedPreference(
                            String.valueOf(m.getOrDefault("key", "")),
                            String.valueOf(m.getOrDefault("value", "")),
                            String.valueOf(m.getOrDefault("category", "other")),
                            ((Number) m.getOrDefault("confidence", 0.0)).doubleValue(),
                            String.valueOf(m.getOrDefault("evidence", ""))
                    ))
                    .filter(p -> p.confidence() >= 0.7 && !p.key().isBlank())
                    .toList();
        } catch (Exception e) {
            log.debug("LLM preference extraction failed: {}", e.getMessage());
            return List.of();
        }
    }

    private record ExtractedPreference(String key, String value, String category,
                                       double confidence, String evidence) {}
}
