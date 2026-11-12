package com.bkanent.common.rpc;

/**
 * 通知消息 RPC 接口。
 */
public interface NotificationRpcService {

    /** Verifies a caller-signed, short-lived permit for one idempotent system reminder. */
    default void sendAuthorizedReminder(AuthorizedNotificationPermit permit) {
        throw new com.bkanent.common.authz.AuthzException(
                com.bkanent.common.authz.AuthzErrorCode.AUTHENTICATION_REQUIRED);
    }

    /**
     * 业务方法：sendStationMessage。
     */
    void sendStationMessage(Long userId, String title, String content);

    /**
     * 业务方法：sendEmailMessage。
     */
    void sendEmailMessage(String receiverAddress, String title, String content);

    /**
     * 业务方法：sendRobotMessage。
     */
    void sendRobotMessage(String channel, String webhookUrl, String title, String content);
}
