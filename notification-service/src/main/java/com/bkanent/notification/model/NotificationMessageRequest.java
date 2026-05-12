package com.bkanent.notification.model;

/**
 * 通用通知消息请求。
 */
public record NotificationMessageRequest(
        /** 业务属性：userId。 */
        Long userId,
        /** 业务属性：receiverAddress。 */
        String receiverAddress,
        /** 业务属性：sceneCode。 */
        String sceneCode,
        /** 业务属性：title。 */
        String title,
        /** 业务属性：content。 */
        String content,
        /** 业务属性：operator。 */
        String operator
) {
}
