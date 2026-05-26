package com.bkanent.notification.service;

public interface NotificationEventConsumeService {

    NotificationConsumeStartResult tryStartConsume(String dedupeKey,
                                                   String eventType,
                                                   String taskId,
                                                   String traceId,
                                                   Long recipientUserId,
                                                   int maxAttempts);

    void markConsumed(String dedupeKey, Long notificationId);

    void markFailed(String dedupeKey, String errorMessage);

    void markDeadLetter(String dedupeKey, String errorMessage);
}
