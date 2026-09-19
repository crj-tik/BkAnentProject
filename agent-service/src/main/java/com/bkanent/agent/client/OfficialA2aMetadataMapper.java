package com.bkanent.agent.client;

import com.bkanent.common.agent.AgentTaskInvokeRequest;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 Supervisor 内部调用上下文映射为官方 A2A metadata。
 *
 * <p>内部 DTO 只在 Supervisor 内部流转，网络边界固定使用官方 A2A message/task
 * 结构。Supervisor 专属字段统一放在 {@code supervisor} 命名空间中，避免污染
 * 官方 metadata；{@code threadId} 和 {@code isStreaming} 保留为 A2A 运行时识别
 * 所需的顶层字段。</p>
 */
public final class OfficialA2aMetadataMapper {

    public static final String VERSION = "1";
    public static final String SUPERVISOR_METADATA_KEY = "supervisor";
    public static final String THREAD_ID_KEY = "threadId";
    public static final String STREAMING_KEY = "isStreaming";

    private OfficialA2aMetadataMapper() {
    }

    public static Map<String, Object> toMetadata(AgentTaskInvokeRequest request, boolean streaming) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        String threadId = firstText(request.taskId(), request.sessionId());
        if (StringUtils.hasText(threadId)) {
            metadata.put(THREAD_ID_KEY, threadId);
        }
        metadata.put(STREAMING_KEY, streaming);

        Map<String, Object> supervisor = new LinkedHashMap<>();
        supervisor.put("version", VERSION);
        putText(supervisor, "sessionId", request.sessionId());
        putText(supervisor, "taskId", request.taskId());
        putText(supervisor, "parentTaskId", request.parentTaskId());
        putText(supervisor, "traceId", request.traceId());
        putText(supervisor, "sourceAgentId", request.sourceAgentId());
        putText(supervisor, "targetAgentId", request.targetAgentId());
        putText(supervisor, "intent", request.intent());
        putText(supervisor, "domain", request.domain());
        putText(supervisor, "expectedOutput", request.expectedOutput());
        putText(supervisor, "idempotencyKey", request.idempotencyKey());
        putList(supervisor, "artifactIds", request.artifactIds());
        putList(supervisor, "constraints", request.constraints());
        if (request.structuredContext() != null && !request.structuredContext().isEmpty()) {
            Map<String, Object> structuredContext = new LinkedHashMap<>();
            request.structuredContext().forEach((key, value) -> {
                if (key != null && value != null) {
                    structuredContext.put(key, value);
                }
            });
            if (!structuredContext.isEmpty()) {
                supervisor.put("structuredContext", Map.copyOf(structuredContext));
            }
        }
        metadata.put(SUPERVISOR_METADATA_KEY, Map.copyOf(supervisor));
        return Map.copyOf(metadata);
    }

    private static void putText(Map<String, Object> target, String key, String value) {
        if (StringUtils.hasText(value)) {
            target.put(key, value);
        }
    }

    private static void putList(Map<String, Object> target, String key, List<String> value) {
        if (value != null && !value.isEmpty()) {
            target.put(key, List.copyOf(value));
        }
    }

    private static String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }
}
