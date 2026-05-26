package com.bkanent.common.agent;

import com.bkanent.common.model.ApiResponse;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class A2aInvokeSupport {

    private static final Map<String, AgentTaskInvokeResponse> IDEMPOTENT_RESPONSES = new ConcurrentHashMap<>();

    private A2aInvokeSupport() {
    }

    public static ApiResponse<AgentTaskInvokeResponse> validate(AgentTaskInvokeRequest request) {
        if (request == null) {
            return ApiResponse.fail(A2aErrorCodes.A2A_BAD_REQUEST, "request must not be null");
        }
        if (isBlank(request.taskId())) {
            return ApiResponse.fail(A2aErrorCodes.A2A_BAD_REQUEST, "taskId must not be blank");
        }
        if (isBlank(request.traceId())) {
            return ApiResponse.fail(A2aErrorCodes.A2A_BAD_REQUEST, "traceId must not be blank");
        }
        if (isBlank(request.targetAgentId())) {
            return ApiResponse.fail(A2aErrorCodes.A2A_BAD_REQUEST, "targetAgentId must not be blank");
        }
        if (isBlank(request.intent())) {
            return ApiResponse.fail(A2aErrorCodes.A2A_BAD_REQUEST, "intent must not be blank");
        }
        return null;
    }

    public static AgentTaskInvokeResponse findDuplicateResponse(AgentTaskInvokeRequest request) {
        if (request == null || isBlank(request.idempotencyKey())) {
            return null;
        }
        return IDEMPOTENT_RESPONSES.get(request.idempotencyKey());
    }

    public static void rememberResponse(AgentTaskInvokeRequest request, AgentTaskInvokeResponse response) {
        if (request == null || response == null || isBlank(request.idempotencyKey())) {
            return;
        }
        IDEMPOTENT_RESPONSES.putIfAbsent(request.idempotencyKey(), response);
    }

    public static String buildIdempotencyKey(String taskId,
                                             String targetAgentId,
                                             String intent,
                                             int retryCount) {
        return String.join(":",
                normalize(taskId),
                normalize(targetAgentId),
                normalize(intent),
                String.valueOf(retryCount));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String normalize(String value) {
        return isBlank(value) ? "unknown" : value.trim();
    }
}
