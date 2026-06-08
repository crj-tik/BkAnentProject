package com.bkanent.agent.service;

import com.bkanent.agent.memory.MemoryStoreClient;
import com.bkanent.agent.milvus.memory.AgentMemoryMilvusService;
import com.bkanent.agent.milvus.core.model.MilvusSearchResult;
import com.bkanent.common.agent.UserPreferenceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * UserPreferenceRetriever 检索用户偏好并注入到 sharedContext。
 */
@Service
public class UserPreferenceRetriever {

    private static final Logger log = LoggerFactory.getLogger(UserPreferenceRetriever.class);

    private final MemoryStoreClient memoryStoreClient;
    private final AgentMemoryMilvusService agentMemoryMilvusService;

    public UserPreferenceRetriever(MemoryStoreClient memoryStoreClient,
                                   AgentMemoryMilvusService agentMemoryMilvusService) {
        this.memoryStoreClient = memoryStoreClient;
        this.agentMemoryMilvusService = agentMemoryMilvusService;
    }

    public Map<String, Object> retrieve(String userId, String userQuery) {
        Map<String, Object> preferences = new LinkedHashMap<>();

        if (userId == null || userId.isBlank()) {
            return preferences;
        }

        // MySQL 精确查询
        List<UserPreferenceRecord> records = memoryStoreClient.getUserPreferences(userId, null);
        for (UserPreferenceRecord record : records) {
            Map<String, Object> pref = new LinkedHashMap<>();
            pref.put("value", record.preferenceValue());
            pref.put("confidence", record.confidence());
            pref.put("category", record.category());
            preferences.put(record.preferenceKey(), pref);
        }

        // Milvus 语义匹配
        if (userQuery != null && !userQuery.isBlank()) {
            try {
                List<MilvusSearchResult> semanticResults = agentMemoryMilvusService.searchKnowledge(
                        "user_preference_knowledge", userQuery, 3);
                for (MilvusSearchResult result : semanticResults) {
                    if (result.metadata() != null && result.metadata().containsKey("preference_key")) {
                        String key = String.valueOf(result.metadata().get("preference_key"));
                        if (!preferences.containsKey(key)) {
                            Map<String, Object> pref = new LinkedHashMap<>();
                            pref.put("value", result.metadata().getOrDefault("preference_value", result.content()));
                            pref.put("confidence", result.score());
                            pref.put("category", result.metadata().getOrDefault("category", "general"));
                            pref.put("source", "semantic");
                            preferences.put(key, pref);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Milvus user preference search skipped: {}", e.getMessage());
            }
        }

        return preferences;
    }
}
