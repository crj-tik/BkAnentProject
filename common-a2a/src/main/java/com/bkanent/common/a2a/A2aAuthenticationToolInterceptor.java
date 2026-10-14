package com.bkanent.common.a2a;

import com.alibaba.cloud.ai.graph.agent.interceptor.*;
import com.bkanent.common.rag.*;

/** Typed execution identity lookup, not tool arguments or ordinary metadata. */
public final class A2aAuthenticationToolInterceptor extends ToolInterceptor {
    @Override public String getName() { return "a2aAuthentication"; }
    @Override public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        String taskId = request.getExecutionContext().flatMap(ToolCallExecutionContext::threadId)
                .orElseThrow(() -> new RagException(RagErrorCode.AUTHENTICATION_FAILED));
        try (var ignored = A2aCredentialContext.openTask(taskId)) {
            ToolCallResponse response = handler.call(request);
            A2aCredentialContext.required();
            return response;
        }
    }
}
