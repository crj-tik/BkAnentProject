package com.bkanent.common.a2a;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Validates and canonicalizes the model output emitted by a SubAgent.
 */
public final class A2aOutputNormalizer {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public A2aOutputNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public A2aOutput normalize(String rawOutput, A2aOutputPolicy policy, boolean jsonOutput) {
        String raw = rawOutput == null ? "" : rawOutput.trim();
        if (raw.length() > policy.maxOutputChars()) {
            throw new A2aOutputException("OUTPUT_TOO_LARGE", "SubAgent output exceeds the configured size limit");
        }
        if (!jsonOutput) {
            if (!StringUtils.hasText(raw)) {
                throw new A2aOutputException("EMPTY_OUTPUT", "SubAgent returned an empty text result");
            }
            String safe = com.bkanent.common.agent.AgentOutputSanitizer.sanitizeText(raw);
            return new A2aOutput(A2aOutputPolicy.TEXT_MODE, null, safe, safe, List.of());
        }

        Map<String, Object> result = parseObject(raw);
        Map<String, Object> sanitized = com.bkanent.common.agent.AgentOutputSanitizer.sanitizeMap(result);
        Object contentType = sanitized.get("contentType");
        if (!(contentType instanceof String text) || !StringUtils.hasText(text)) {
            sanitized.put("contentType", policy.contentType());
        }
        String summary = resolveSummary(sanitized, policy.contentType());
        sanitized.put("summary", summary);
        List<String> nextHints = normalizeNextHints(sanitized.get("nextHints"));
        sanitized.put("nextHints", nextHints);
        try {
            String canonical = objectMapper.writeValueAsString(sanitized);
            return new A2aOutput(A2aOutputPolicy.JSON_MODE,
                    Collections.unmodifiableMap(new LinkedHashMap<>(sanitized)), canonical, summary, nextHints);
        }
        catch (JsonProcessingException exception) {
            throw new A2aOutputException("INVALID_STRUCTURED_OUTPUT", "SubAgent result cannot be serialized");
        }
    }

    private Map<String, Object> parseObject(String raw) {
        String candidate = unwrapCodeFence(raw);
        if (!StringUtils.hasText(candidate)) {
            throw new A2aOutputException("INVALID_STRUCTURED_OUTPUT", "SubAgent returned an empty JSON result");
        }
        try {
            JsonNode root = objectMapper.readTree(candidate);
            if (root == null || !root.isObject()) {
                throw new A2aOutputException("INVALID_STRUCTURED_OUTPUT", "SubAgent result must be a JSON object");
            }
            return objectMapper.convertValue(root, MAP_TYPE);
        }
        catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new A2aOutputException("INVALID_STRUCTURED_OUTPUT", "SubAgent result is not valid JSON");
        }
    }

    private String unwrapCodeFence(String raw) {
        String candidate = raw == null ? "" : raw.trim();
        if (!candidate.startsWith("```")) {
            return candidate;
        }
        int firstLineEnd = candidate.indexOf('\n');
        int lastFence = candidate.lastIndexOf("```");
        if (firstLineEnd < 0 || lastFence <= firstLineEnd) {
            throw new A2aOutputException("INVALID_STRUCTURED_OUTPUT", "SubAgent JSON code fence is incomplete");
        }
        return candidate.substring(firstLineEnd + 1, lastFence).trim();
    }

    private String resolveSummary(Map<String, Object> result, String contentType) {
        Object summary = result.get("summary");
        if (summary instanceof String text && StringUtils.hasText(text)) {
            return text.trim();
        }
        for (String key : List.of("conclusion", "message", "result")) {
            Object candidate = result.get(key);
            if (candidate instanceof String text && StringUtils.hasText(text)) {
                return text.trim();
            }
        }
        return contentType + " result completed";
    }

    private List<String> normalizeNextHints(Object value) {
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        if (value instanceof Collection<?> collection) {
            collection.forEach(item -> {
                if (item instanceof String text && StringUtils.hasText(text)) {
                    hints.add(text.trim());
                }
            });
        }
        else if (value instanceof String text && StringUtils.hasText(text)) {
            hints.add(text.trim());
        }
        return List.copyOf(new ArrayList<>(hints));
    }
}
