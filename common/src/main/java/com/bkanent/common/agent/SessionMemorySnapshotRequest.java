package com.bkanent.common.agent;

import java.util.Map;

/**
 * SessionMemorySnapshotRequest 表示带版本号的会话记忆快照写入请求。
 */
public record SessionMemorySnapshotRequest(
        String sessionId,
        String taskId,
        Integer version,
        Map<String, Object> memory,
        String summary,
        String traceId
) {
}
