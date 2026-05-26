package com.bkanent.common.agent;

import java.time.LocalDateTime;

/**
 * ApprovalDecision 表示审批结果。
 */
public record ApprovalDecision(
        String approvalId,
        ApprovalStatus status,
        String reviewerId,
        String feedback,
        LocalDateTime reviewedAt,
        String traceId
) {
}
