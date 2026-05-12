package com.bkanent.notification.model;

/**
 * 机器人消息请求。
 */
public record RobotMessageRequest(
        /** 业务属性：channel。 */
        String channel,
        /** 业务属性：webhookUrl。 */
        String webhookUrl,
        /** 业务属性：title。 */
        String title,
        /** 业务属性：content。 */
        String content,
        /** 业务属性：operator。 */
        String operator
) {
}
