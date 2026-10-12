package com.bkanent.agent.rag;

import com.bkanent.agent.milvus.core.model.MilvusSearchResult;
import com.bkanent.common.rag.*;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Legacy addressing and quantity mapping, not a second retrieval implementation. */
public final class AgentRagCompatibility {
    private static final Map<String, String> ALIASES = Map.of("agent_knowledge", "supervisor.knowledge",
            "listing_knowledge", "listing.knowledge", "user_preference_knowledge", "memory.preference",
            "system_constraint_knowledge", "memory.constraint");
    private AgentRagCompatibility() { }
    public static String space(String alias, String defaultSpace) {
        if (alias == null || alias.isBlank()) return defaultSpace;
        if (ALIASES.containsValue(alias)) return alias;
        String space = ALIASES.get(alias);
        if (space == null) throw new RagException(RagErrorCode.INVALID_ARGUMENT, "collectionName");
        return space;
    }
    public static String alias(String space) {
        return ALIASES.entrySet().stream().filter(entry -> entry.getValue().equals(space)).map(Map.Entry::getKey)
                .findFirst().orElseThrow(() -> new RagException(RagErrorCode.INVALID_ARGUMENT, "spaceId"));
    }
    public static int topK(Integer value, int fallback) {
        int count = value == null ? fallback : value;
        if (count < 1 || count > 200) throw new RagException(RagErrorCode.INVALID_ARGUMENT, "topK");
        return count;
    }
    public static RagSearchRequest knowledge(String space, String query, int topK) {
        topK(topK, 5);
        int pool = Math.max(100, topK);
        return new RagSearchRequest(1, UUID.randomUUID().toString(), space, query, List.of(),
                new RagRecallOptions(true, null, pool, pool), null, true, pool, topK, RagFailurePolicy.STRICT).normalized();
    }
    public static void listingCounts(int keyword, int vector, int topK) {
        if (keyword < 1 || keyword > 500 || vector < 1 || vector > 500
                || keyword + vector < topK || topK < 1 || topK > 200)
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "recall");
    }
    public static MilvusSearchResult result(String space, RagCandidate candidate, RagSearchResponse response) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        candidate.metadata().forEach((key, value) -> metadata.put(key, scalar(value)));
        metadata.put("projectionVersion", candidate.projectionVersion());
        metadata.put("schemaVersion", candidate.schemaVersion());
        metadata.put("scoreKind", candidate.scoreKind().name());
        metadata.put("scoringVersion", candidate.scoringVersion());
        metadata.put("degraded", response.degraded());
        metadata.put("failedStages", response.failedStages());
        metadata.put("effectiveMode", response.effectiveMode().name());
        return new MilvusSearchResult(alias(space), candidate.documentId(), candidate.sourceType(), candidate.sourceId(),
                candidate.content(), candidate.relevanceScore(), metadata);
    }
    private static Object scalar(RagValue value) {
        return switch (value.type()) {
            case NUMBER -> new BigDecimal(value.scalar());
            case BOOLEAN -> Boolean.valueOf(value.scalar());
            case NUMBER_LIST, STRING_LIST -> value.values();
            default -> value.scalar();
        };
    }
}
