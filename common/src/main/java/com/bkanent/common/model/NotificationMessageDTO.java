package com.bkanent.common.model;

/**
 * 通知消息传输对象。
 */
public record NotificationMessageDTO(
        /** 业务属性：userId。 */
        Long userId,
        /** 业务属性：title。 */
        String title,
        /** 业务属性：content。 */
        String content,
        /** 业务属性：channel。 */
        String channel
) {
}
