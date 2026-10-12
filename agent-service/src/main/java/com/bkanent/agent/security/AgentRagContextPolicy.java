package com.bkanent.agent.security;

import com.bkanent.agent.rag.AgentRagCompatibility;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Ordinary request context is public model/checkpoint data, never an authentication channel. */
public final class AgentRagContextPolicy {
    private AgentRagContextPolicy() { }

    public static Map<String, Object> normalize(Map<String, Object> context) {
        if (context == null) return null;
        rejectCredentials(context, 0);
        Map<String, Object> result = new LinkedHashMap<>(context);
        if (result.containsKey("allowKnowledgeSearch") && !(result.get("allowKnowledgeSearch") instanceof Boolean))
            throw new IllegalArgumentException("INVALID_ARGUMENT");
        if (result.containsKey("spaceId")) {
            if (!(result.get("spaceId") instanceof String space)) throw new IllegalArgumentException("INVALID_ARGUMENT");
            result.put("spaceId", AgentRagCompatibility.space(space, "supervisor.knowledge"));
        }
        if (result.containsKey("collectionName")) {
            if (!(result.remove("collectionName") instanceof String alias)) throw new IllegalArgumentException("INVALID_ARGUMENT");
            String space = AgentRagCompatibility.space(alias, "supervisor.knowledge");
            if (result.containsKey("spaceId") && !space.equals(result.get("spaceId"))) throw new IllegalArgumentException("INVALID_ARGUMENT");
            result.put("spaceId", space);
        }
        if (result.containsKey("topK")) {
            Object value = result.get("topK");
            if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                    || number.doubleValue() != number.intValue()) throw new IllegalArgumentException("INVALID_ARGUMENT");
            result.put("topK", AgentRagCompatibility.topK(number.intValue(), 5));
        }
        return Collections.unmodifiableMap(result);
    }

    private static void rejectCredentials(Object value, int depth) {
        if (depth > 32) throw new IllegalArgumentException("INVALID_ARGUMENT");
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("INVALID_ARGUMENT");
                String name = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
                if (name.contains("token") || name.contains("credential") || name.contains("authorization")
                        || name.contains("secret") || name.equals("ragauthenvelope") || name.equals("authentication"))
                    throw new IllegalArgumentException("CREDENTIAL_CONTEXT_FORBIDDEN");
                rejectCredentials(entry.getValue(), depth + 1);
            }
        } else if (value instanceof List<?> list) {
            list.forEach(item -> rejectCredentials(item, depth + 1));
        } else if (value != null && !(value instanceof String || value instanceof Number || value instanceof Boolean)) {
            throw new IllegalArgumentException("INVALID_ARGUMENT");
        }
    }
}
