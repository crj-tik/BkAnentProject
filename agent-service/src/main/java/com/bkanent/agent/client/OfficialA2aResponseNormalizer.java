package com.bkanent.agent.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import io.a2a.spec.DataPart;
import io.a2a.spec.Part;
import io.a2a.spec.TextPart;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 将官方 A2A 的 Message、Task、Artifact part 统一转换为 Supervisor 内部响应。
 *
 * <p>该组件只处理协议结果到内部模型的映射，不改变 Supervisor 的本地 taskId。
 * 远端 A2A Task ID 通过 structuredOutput.remoteTaskId 保留。</p>
 */
@Component
public final class OfficialA2aResponseNormalizer {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE =
            new TypeReference<>() {
            };

    private final ObjectMapper objectMapper;

    public OfficialA2aResponseNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AgentTaskInvokeResponse normalize(RegisteredAgentDescriptor descriptor,
                                              AgentTaskInvokeRequest request,
                                              String output,
                                              String status,
                                              String remoteTaskId,
                                              Collection<String> artifactIds,
                                              String errorMessage,
                                              Collection<? extends Part<?>> parts) {
        String normalizedOutput = StringUtils.hasText(output) ? output : extractText(parts);
        Map<String, Object> structuredOutput = new LinkedHashMap<>();
        Map<String, Object> parsedOutput = parseStructuredOutput(normalizedOutput, parts);
        structuredOutput.putAll(parsedOutput);
        structuredOutput.putIfAbsent("officialA2a", true);
        if (!structuredOutput.containsKey("output")) {
            structuredOutput.put("output", normalizedOutput);
        } else if (StringUtils.hasText(normalizedOutput)) {
            structuredOutput.put("rawOutput", normalizedOutput);
        }
        if (StringUtils.hasText(remoteTaskId)) {
            structuredOutput.put("remoteTaskId", remoteTaskId);
        }
        if (StringUtils.hasText(errorMessage)) {
            structuredOutput.put("error", errorMessage);
        }

        List<String> normalizedArtifactIds = normalizeArtifactIds(artifactIds);
        List<String> nextHints = normalizeNextHints(structuredOutput.get("nextHints"));
        String normalizedStatus = StringUtils.hasText(status) ? status : "UNKNOWN";
        String summary = textValue(structuredOutput.get("summary"), normalizedOutput);
        String localTaskId = request == null || !StringUtils.hasText(request.taskId())
                ? remoteTaskId
                : request.taskId();

        return new AgentTaskInvokeResponse(
                request == null ? null : request.sessionId(),
                localTaskId,
                descriptor == null ? null : descriptor.agentId(),
                normalizedStatus,
                Collections.unmodifiableMap(new LinkedHashMap<>(structuredOutput)),
                normalizedArtifactIds,
                nextHints,
                summary,
                request == null ? null : request.traceId()
        );
    }

    public String extractText(Collection<? extends Part<?>> parts) {
        if (parts == null || parts.isEmpty()) {
            return "";
        }
        List<String> texts = new ArrayList<>();
        for (Part<?> part : parts) {
            if (part instanceof TextPart textPart && StringUtils.hasText(textPart.getText())) {
                texts.add(textPart.getText().trim());
            }
        }
        return String.join(System.lineSeparator(), texts);
    }

    private Map<String, Object> parseStructuredOutput(String output,
                                                       Collection<? extends Part<?>> parts) {
        Map<String, Object> fromData = new LinkedHashMap<>();
        if (parts != null) {
            for (Part<?> part : parts) {
                if (part instanceof DataPart dataPart && dataPart.getData() != null
                        && !dataPart.getData().isEmpty()) {
                    fromData.putAll(dataPart.getData());
                }
            }
        }
        if (!fromData.isEmpty()) {
            return fromData;
        }

        Map<String, Object> fromText = new LinkedHashMap<>();
        if (parts != null) {
            for (Part<?> part : parts) {
                if (part instanceof TextPart textPart) {
                    parseJsonObject(textPart.getText()).ifPresent(fromText::putAll);
                }
            }
        }
        parseJsonObject(output).ifPresent(fromText::putAll);
        return fromText;
    }

    private java.util.Optional<Map<String, Object>> parseJsonObject(String raw) {
        String candidate = unwrapCodeFence(raw);
        if (!StringUtils.hasText(candidate)) {
            return java.util.Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(candidate);
            if (root == null || !root.isObject()) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.ofNullable(objectMapper.convertValue(root, MAP_TYPE));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return java.util.Optional.empty();
        }
    }

    private String unwrapCodeFence(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String candidate = raw.trim();
        if (!candidate.startsWith("```")) {
            return candidate;
        }
        int firstLineEnd = candidate.indexOf('\n');
        int lastFence = candidate.lastIndexOf("```");
        if (firstLineEnd < 0 || lastFence <= firstLineEnd) {
            return candidate;
        }
        return candidate.substring(firstLineEnd + 1, lastFence).trim();
    }

    private List<String> normalizeArtifactIds(Collection<String> artifactIds) {
        if (artifactIds == null || artifactIds.isEmpty()) {
            return List.of();
        }
        return List.copyOf(new LinkedHashSet<>(artifactIds.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList()));
    }

    private List<String> normalizeNextHints(Object value) {
        if (value instanceof Collection<?> collection) {
            return List.copyOf(collection.stream()
                    .filter(item -> item != null && StringUtils.hasText(String.valueOf(item)))
                    .map(item -> String.valueOf(item).trim())
                    .toList());
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            return List.of(text.trim());
        }
        return List.of();
    }

    private String textValue(Object value, String fallback) {
        if (value instanceof String text && StringUtils.hasText(text)) {
            return text.trim();
        }
        return fallback == null ? "" : fallback;
    }
}
