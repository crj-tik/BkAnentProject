package com.bkanent.common.agent;

import java.util.Map;

/**
 * ApprovalRequest 表示通用审批请求。
 */
public record ApprovalRequest(
        String approvalId,
        String taskId,
        String sessionId,
        String approvalType,
        String subjectType,
        String subjectId,
        Integer subjectVersion,
        String title,
        String summary,
        Map<String, Object> payload,
        String approveNextNode,
        String rejectNextNode,
        String terminateNextNode,
        Integer retryCount,
        Integer maxRetryCount,
        String traceId
) {
}
