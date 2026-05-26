package com.bkanent.common.agent;

import java.util.Map;

/**
 * SessionMemoryUpsertRequest 表示会话共享记忆写入请求。
 */
public record SessionMemoryUpsertRequest(
        String sessionId,
        String userId,
        Map<String, Object> memory,
        String summary,
        String traceId
) {
}
