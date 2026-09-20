package com.bkanent.common.a2a;

import java.util.List;
import java.util.Map;

/**
 * Renders only the supported Supervisor metadata fields into model context.
 */
public final class A2aSupervisorContextRenderer {

    private static final int MAX_CONTEXT_CHARS = 12_000;
    private static final List<String> SUPERVISOR_FIELDS = List.of(
            "version", "sessionId", "taskId", "parentTaskId", "traceId", "sourceAgentId",
            "targetAgentId", "intent", "domain", "expectedOutput", "artifactIds", "constraints",
            "structuredContext"
    );

    private A2aSupervisorContextRenderer() {
    }

    public static String render(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return "";
        }
        StringBuilder rendered = new StringBuilder();
        append(rendered, "threadId", context.get("threadId"));
        append(rendered, "isStreaming", context.get("isStreaming"));
        Object supervisor = context.get("supervisor");
        if (supervisor instanceof Map<?, ?> supervisorMap) {
            SUPERVISOR_FIELDS.forEach(field -> append(rendered, "supervisor." + field, supervisorMap.get(field)));
        }
        return rendered.length() > MAX_CONTEXT_CHARS
                ? rendered.substring(0, MAX_CONTEXT_CHARS) + "..."
                : rendered.toString();
    }

    private static void append(StringBuilder target, String key, Object value) {
        if (value != null) {
            target.append(key).append('=').append(value).append('\n');
        }
    }
}
