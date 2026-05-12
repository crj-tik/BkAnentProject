package com.bkanent.notification.model;

/**
 * 站内消息已读请求。
 */
public record NotificationReadRequest(
        /** 业务属性：userId。 */
        Long userId
) {
}
