package com.bkanent.common.memory;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

/** Shared validation and bounded rendering for durable and collaboration export payloads. */
public final class MemorySharedExportPolicy {
    private static final Pattern PRIVATE_FIELD = Pattern.compile(
            "(?i).*(password|passwd|secret|token|credential|authorization|authheader|beacon|cookie|ticket|prompt|thought|reasoning|chainofthought|transcript|conversationhistory|checkpoint|systemmessage|desensitizationmap|redactionmap|maskingmap|maskmap|privatekey|toolarguments|arguments|input|toolcall|stacktrace|raw|internal).*");

    private MemorySharedExportPolicy() { }

    public static String project(String brief, MemoryJsonValue payload) {
        return project(brief, payload, MemorySharedExportFields.MAX_RENDERED_UTF8_BYTES);
    }

    public static String project(String brief, MemoryJsonValue payload, int configuredMaxUtf8Bytes) {
        if (configuredMaxUtf8Bytes < 1 || payload == null || payload.type() != MemoryJsonValue.Type.OBJECT
                || !MemorySharedExportFields.allowedRootFields().containsAll(payload.properties().keySet())) invalid();
        int maxBytes = Math.min(configuredMaxUtf8Bytes, MemorySharedExportFields.MAX_RENDERED_UTF8_BYTES);
        StringBuilder text = new StringBuilder();
        int[] bytes = {0};
        if (brief != null && !brief.isBlank()) {
            String normalizedBrief = brief.strip();
            if (com.bkanent.common.agent.AgentOutputSanitizer.containsSensitiveText(normalizedBrief)) invalid();
            append(text, bytes, normalizedBrief, maxBytes);
            append(text, bytes, "\n", maxBytes);
        }
        payload.properties().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> append(text, bytes, entry.getKey(), entry.getValue(), 0, maxBytes));
        while (!text.isEmpty() && Character.isWhitespace(text.charAt(text.length() - 1))) text.setLength(text.length() - 1);
        if (text.isEmpty()) invalid();
        if (text.toString().getBytes(StandardCharsets.UTF_8).length > maxBytes) budgetExceeded();
        return text.toString();
    }

    private static void append(StringBuilder out, int[] bytes, String key, MemoryJsonValue value,
                               int depth, int maxBytes) {
        if (depth > 8 || PRIVATE_FIELD.matcher(key).matches()) invalid();
        switch (value.type()) {
            case NULL -> { }
            case STRING, NUMBER, BOOLEAN -> {
                String scalar = value.scalar();
                if (value.type() == MemoryJsonValue.Type.STRING
                        && com.bkanent.common.agent.AgentOutputSanitizer.containsSensitiveText(scalar)) invalid();
                append(out, bytes, key, maxBytes);
                append(out, bytes, ": ", maxBytes);
                append(out, bytes, scalar, maxBytes);
                append(out, bytes, "\n", maxBytes);
            }
            case ARRAY -> {
                if (value.elements().size() > 100) invalid();
                for (MemoryJsonValue child : value.elements()) append(out, bytes, key, child, depth + 1, maxBytes);
            }
            case OBJECT -> value.properties().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> append(out, bytes, entry.getKey(), entry.getValue(), depth + 1, maxBytes));
        }
    }

    private static void append(StringBuilder out, int[] bytes, String value, int maxBytes) {
        bytes[0] += value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes[0] > maxBytes) budgetExceeded();
        out.append(value);
    }

    private static void invalid() { throw new MemoryException(MemoryErrorCode.INVALID_ARGUMENT); }
    private static void budgetExceeded() { throw new MemoryException(MemoryErrorCode.CONTEXT_BUDGET_EXCEEDED); }
}
