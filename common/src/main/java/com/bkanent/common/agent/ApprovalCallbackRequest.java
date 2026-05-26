package com.bkanent.common.agent;

/**
 * ApprovalCallbackRequest 表示审批回调请求。
 */
public record ApprovalCallbackRequest(
        String approvalId,
        String taskId,
        String sessionId,
        ApprovalStatus status,
        String reviewerId,
        String feedback,
        String traceId
) {
}
