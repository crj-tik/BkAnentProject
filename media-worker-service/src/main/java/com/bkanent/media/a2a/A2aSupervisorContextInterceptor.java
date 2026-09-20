package com.bkanent.media.a2a;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.bkanent.common.a2a.A2aSupervisorContextRenderer;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.Map;

/** 将官方 A2A Supervisor metadata 注入媒体 Agent 的模型上下文。 */
public final class A2aSupervisorContextInterceptor extends ModelInterceptor {

    @Override
    public String getName() {
        return "media-a2a-supervisor-context";
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        Map<String, Object> context = request.getContext();
        if (context == null || context.isEmpty()) {
            return handler.call(request);
        }
        String supervisorContext = A2aSupervisorContextRenderer.render(context);
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
                        + "\nHonor the requested intent, constraints, expected output, and correlation."
                        + " Do not expose internal correlation identifiers in the user-facing answer."))
                .build());
    }

}
