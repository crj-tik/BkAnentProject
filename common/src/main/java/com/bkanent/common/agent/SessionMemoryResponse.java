package com.bkanent.common.agent;

import java.util.Map;

/**
 * SessionMemoryResponse 表示会话共享记忆查询结果。
 */
public record SessionMemoryResponse(
        String sessionId,
        String userId,
        Map<String, Object> memory,
        String summary,
        String traceId
) {
}
