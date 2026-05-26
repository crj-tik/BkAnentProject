package com.bkanent.notification.model;

import java.time.LocalDateTime;

public record NotificationWorkflowEventConsumeResponse(
        Long id,
        String dedupeKey,
        String eventType,
        String taskId,
        String traceId,
        Long recipientUserId,
        String consumeStatus,
        Integer attemptCount,
        Long notificationId,
        String errorMessage,
        LocalDateTime consumedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
