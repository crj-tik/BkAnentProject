package com.bkanent.agent.graph.node;

import com.bkanent.agent.graph.SupervisorGraphNode;
import com.bkanent.agent.graph.SupervisorGraphState;
import com.bkanent.agent.memory.MemoryStoreClient;
import com.bkanent.agent.service.UserPreferenceRetriever;
import com.bkanent.common.agent.SystemConstraintRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class LoadSessionNode implements SupervisorGraphNode {

    private static final Logger log = LoggerFactory.getLogger(LoadSessionNode.class);

    private static final Set<String> DOMAIN_KEYWORDS = Set.of(
            "contract", "settlement", "notification", "marketing", "trade", "listing"
    );

    private final MemoryStoreClient memoryStoreClient;
    private final UserPreferenceRetriever userPreferenceRetriever;

    public LoadSessionNode(MemoryStoreClient memoryStoreClient,
                           UserPreferenceRetriever userPreferenceRetriever) {
        this.memoryStoreClient = memoryStoreClient;
        this.userPreferenceRetriever = userPreferenceRetriever;
    }

    @Override
    public SupervisorGraphState apply(SupervisorGraphState state) {
        Map<String, Object> sharedContext = new LinkedHashMap<>();

        // 加载 session 记忆
        memoryStoreClient.getSessionMemory(state.sessionId())
                .map(memory -> memory.memory() == null ? Map.<String, Object>of() : memory.memory())
                .ifPresent(sharedContext::putAll);

        // 加载用户偏好
        try {
            Map<String, Object> userPrefs = userPreferenceRetriever.retrieve(
                    state.userId(), state.userMessage());
            if (!userPrefs.isEmpty()) {
                sharedContext.put("userPreferences", userPrefs);
            }
        } catch (Exception e) {
            log.debug("Failed to load user preferences: {}", e.getMessage());
        }

        // 加载相关系统约束（基于 userMessage 的关键词匹配）
        try {
            List<SystemConstraintRecord> constraints = loadRelevantConstraints(state.userMessage());
            if (!constraints.isEmpty()) {
                sharedContext.put("systemConstraints", constraints.stream()
                        .map(c -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("key", c.constraintKey());
                            m.put("text", c.constraintText());
                            m.put("category", c.category());
                            m.put("tags", c.tags());
                            return m;
                        }).toList());
            }
        } catch (Exception e) {
            log.debug("Failed to load system constraints: {}", e.getMessage());
        }

        if (state.sharedContext() != null) {
            sharedContext.putAll(state.sharedContext());
        }
        return state.withSharedContext(Map.copyOf(sharedContext));
    }

    private List<SystemConstraintRecord> loadRelevantConstraints(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return memoryStoreClient.getSystemConstraints("domain_rule");
        }
        String lower = userMessage.toLowerCase();
        List<String> matchedTags = new ArrayList<>();
        for (String kw : DOMAIN_KEYWORDS) {
            if (lower.contains(kw)) {
                matchedTags.add(kw);
            }
        }
        if (matchedTags.isEmpty()) {
            return memoryStoreClient.getSystemConstraints("domain_rule");
        }
        String tags = String.join(",", matchedTags);
        return memoryStoreClient.searchSystemConstraints(tags);
    }
}
