package com.bkanent.notification.service;

public record NotificationConsumeStartResult(
        boolean accepted,
        boolean deadLettered,
        int attemptCount,
        String consumeStatus
) {
}
