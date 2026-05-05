package com.bkanent.common.model;

public record NotificationMessageDTO(
        Long userId,
        String title,
        String content,
        String channel
) {
}
