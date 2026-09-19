package com.bkanent.listing.a2a;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.Map;

/** 将官方 A2A Supervisor metadata 注入房源 Agent 的模型上下文。 */
public final class A2aSupervisorContextInterceptor extends ModelInterceptor {

    private static final int MAX_CONTEXT_CHARS = 12_000;

    @Override
    public String getName() {
        return "listing-a2a-supervisor-context";
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        Map<String, Object> context = request.getContext();
        if (context == null || context.isEmpty()) {
            return handler.call(request);
        }
        String supervisorContext = renderContext(context);
        if (supervisorContext.isBlank()) {
            return handler.call(request);
        }
        String existingSystemPrompt = request.getSystemMessage() == null
                ? ""
                : request.getSystemMessage().getText();
        return handler.call(ModelRequest.builder(request)
                .systemMessage(new SystemMessage(existingSystemPrompt
                        + "\n\n[Supervisor execution context]\n"
                        + supervisorContext
                        + "\nHonor the requested domain, constraints, expected output, and correlation."
                        + " Do not expose internal correlation identifiers in the user-facing answer."))
                .build());
    }

    private String renderContext(Map<String, Object> context) {
        StringBuilder rendered = new StringBuilder();
        append(rendered, "threadId", context.get("threadId"));
        append(rendered, "isStreaming", context.get("isStreaming"));
        append(rendered, "supervisor", context.get("supervisor"));
        return rendered.length() > MAX_CONTEXT_CHARS
                ? rendered.substring(0, MAX_CONTEXT_CHARS) + "..."
                : rendered.toString();
    }

    private void append(StringBuilder target, String key, Object value) {
        if (value != null) {
            target.append(key).append('=').append(value).append('\n');
        }
    }
}
