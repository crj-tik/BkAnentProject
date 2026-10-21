package com.bkanent.memory.service;

import com.bkanent.common.memory.MemoryException;
import com.bkanent.common.memory.MemoryJsonValue;
import com.bkanent.common.memory.MemorySharedExportSubmitRequest;
import com.bkanent.common.memory.MemoryErrorCode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

/** Defines the bounded durable collaboration projection; it excludes credentials and internal reasoning fields. */
@Component
public class MemorySharePayloadPolicy {
    private static final Set<String> ROOT_FIELDS = Set.of("result", "summary", "facts", "fact", "decisions",
            "decision", "evidence", "status", "outcome", "recommendations", "citations", "links");
    private static final Pattern PRIVATE_FIELD = Pattern.compile("(?i).*(password|passwd|secret|token|credential|authorization|authheader|cookie|prompt|thought|reasoning|chainofthought|transcript|conversationhistory|checkpoint|systemmessage).*" );
    private static final Pattern SECRET_VALUE = Pattern.compile("(?i).*(bearer\\s+[A-Za-z0-9._~+/-]{12,}|(?:api[_-]?key|password|secret|access[_-]?token)\\s*[:=]\\s*\\S+|-----BEGIN [A-Z ]*PRIVATE KEY-----).*" );
    private final int maxUtf8Bytes;

    public MemorySharePayloadPolicy(com.bkanent.memory.config.MemorySharingProperties properties) {
        this.maxUtf8Bytes = properties.getMaxExportUtf8Bytes();
    }

    public String project(MemorySharedExportSubmitRequest request) {
        return project(request.brief(), request.payload());
    }

    public String project(String brief, MemoryJsonValue payload) {
        if (!ROOT_FIELDS.containsAll(payload.properties().keySet())) invalid();
        StringBuilder text = new StringBuilder();
        if (brief != null && !brief.isBlank()) text.append(brief.strip()).append('\n');
        payload.properties().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> append(text, entry.getKey(), entry.getValue(), 0));
        while (!text.isEmpty() && Character.isWhitespace(text.charAt(text.length() - 1))) text.setLength(text.length() - 1);
        if (text.isEmpty()) invalid();
        if (text.toString().getBytes(StandardCharsets.UTF_8).length > maxUtf8Bytes) budgetExceeded();
        return text.toString();
    }

    private static void append(StringBuilder out, String key, MemoryJsonValue value, int depth) {
        if (depth > 8 || PRIVATE_FIELD.matcher(key).matches()) invalid();
        switch (value.type()) {
            case NULL -> { }
            case STRING, NUMBER, BOOLEAN -> {
                String scalar = value.scalar();
                if (value.type() == MemoryJsonValue.Type.STRING && SECRET_VALUE.matcher(scalar).matches()) invalid();
                out.append(key).append(": ").append(scalar).append('\n');
            }
            case ARRAY -> {
                if (value.elements().size() > 100) invalid();
                for (MemoryJsonValue child : value.elements()) append(out, key, child, depth + 1);
            }
            case OBJECT -> {
                for (var nested : value.properties().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).toList()) {
                    append(out, nested.getKey(), nested.getValue(), depth + 1);
                }
            }
        }
        if (out.toString().getBytes(StandardCharsets.UTF_8).length > 24576) budgetExceeded();
    }

    private static void invalid() { throw new MemoryException(MemoryErrorCode.INVALID_ARGUMENT); }
    private static void budgetExceeded() { throw new MemoryException(MemoryErrorCode.CONTEXT_BUDGET_EXCEEDED); }
}
