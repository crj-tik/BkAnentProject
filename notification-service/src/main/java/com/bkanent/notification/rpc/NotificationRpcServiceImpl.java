package com.bkanent.notification.rpc;

import com.bkanent.common.rpc.NotificationRpcService;
import com.bkanent.common.rpc.AuthorizedNotificationPermit;
import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import org.apache.dubbo.config.annotation.DubboService;
import com.bkanent.notification.security.AuthorizedNotificationPermitVerifier;
import com.bkanent.notification.service.NotificationManagementService;
import java.time.Instant;

/**
 * 通知消息 RPC 实现。
 */
@DubboService
public class NotificationRpcServiceImpl implements NotificationRpcService {

    private final AuthorizedNotificationPermitVerifier permitVerifier;
    private final NotificationManagementService notificationManagementService;

    public NotificationRpcServiceImpl(AuthorizedNotificationPermitVerifier permitVerifier,
                                      NotificationManagementService notificationManagementService) {
        this.permitVerifier = permitVerifier;
        this.notificationManagementService = notificationManagementService;
    }

    @Override
    public void sendAuthorizedReminder(AuthorizedNotificationPermit permit) {
        try {
            permitVerifier.verify(permit, Instant.now());
        } catch (IllegalArgumentException invalid) {
            throw new AuthzException(AuthzErrorCode.AUTHENTICATION_FAILED);
        }
        notificationManagementService.sendAuthorizedReminder(permit);
    }

    @Override
    public void sendStationMessage(Long userId, String title, String content) {
        throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
    }

    @Override
    public void sendEmailMessage(String receiverAddress, String title, String content) {
        throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
    }

    @Override
    public void sendRobotMessage(String channel, String webhookUrl, String title, String content) {
        throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
    }
}
