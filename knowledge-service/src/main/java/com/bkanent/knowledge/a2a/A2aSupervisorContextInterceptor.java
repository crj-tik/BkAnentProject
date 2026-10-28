package com.bkanent.knowledge.a2a;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.bkanent.common.a2a.A2aSupervisorContextRenderer;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class A2aSupervisorContextInterceptor extends ModelInterceptor {
    @Override public String getName() { return "knowledge-a2a-supervisor-context"; }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        Map<String, Object> context = request.getContext();
        if (context == null || context.isEmpty()) return handler.call(request);
        String rendered = A2aSupervisorContextRenderer.render(safeContext(context));
        if (rendered.isBlank()) return handler.call(request);
        String existing = request.getSystemMessage() == null ? "" : request.getSystemMessage().getText();
        return handler.call(ModelRequest.builder(request).systemMessage(new SystemMessage(existing
                + "\n\n[Supervisor execution context]\n" + rendered
                + "\nHonor the requested domain and constraints. Do not expose internal correlation identifiers."))
                .build());
    }

    static Map<String, Object> safeContext(Map<String, Object> context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        addIdentifier(safe, "threadId", context.get("threadId"));
        if (context.get("isStreaming") instanceof Boolean streaming) safe.put("isStreaming", streaming);
        Object raw = context.get("supervisor");
        if (raw instanceof Map<?, ?> supervisor) {
            Map<String, Object> safeSupervisor = new LinkedHashMap<>();
            for (String field : List.of("sessionId", "taskId", "parentTaskId", "traceId", "sourceAgentId", "targetAgentId")) {
                addIdentifier(safeSupervisor, field, supervisor.get(field));
            }
            if ("knowledge".equals(supervisor.get("domain"))) safeSupervisor.put("domain", "knowledge");
            Object artifactIds = supervisor.get("artifactIds");
            if (artifactIds instanceof Iterable<?> values) {
                List<String> safeIds = new ArrayList<>();
                for (Object value : values) {
                    if (value instanceof String text && identifier(text)) safeIds.add(text);
                }
                if (!safeIds.isEmpty()) safeSupervisor.put("artifactIds", List.copyOf(safeIds));
            }
            if (!safeSupervisor.isEmpty()) safe.put("supervisor", Map.copyOf(safeSupervisor));
        }
        return Map.copyOf(safe);
    }

    private static void addIdentifier(Map<String, Object> target, String field, Object value) {
        if (value instanceof String text && identifier(text)) target.put(field, text);
    }

    private static boolean identifier(String value) {
        return value != null && value.matches("[A-Za-z0-9._:-]{1,128}");
    }
}
