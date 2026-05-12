package com.bkanent.notification.model;

import java.time.LocalDateTime;

/**
 * 通知列表项响应。
 */
public record NotificationListItemResponse(
        /** 业务属性：id。 */
        Long id,
        /** 业务属性：recipientUserId。 */
        Long recipientUserId,
        /** 业务属性：channel。 */
        String channel,
        /** 业务属性：sceneCode。 */
        String sceneCode,
        /** 业务属性：title。 */
        String title,
        /** 业务属性：content。 */
        String content,
        /** 业务属性：receiverAddress。 */
        String receiverAddress,
        /** 业务属性：sendStatus。 */
        String sendStatus,
        /** 业务属性：readStatus。 */
        String readStatus,
        /** 业务属性：readTime。 */
        LocalDateTime readTime,
        /** 业务属性：sendTime。 */
        LocalDateTime sendTime
) {
}
