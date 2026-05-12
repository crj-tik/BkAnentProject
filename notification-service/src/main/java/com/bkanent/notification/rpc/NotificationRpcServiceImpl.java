package com.bkanent.notification.rpc;

import com.bkanent.common.rpc.NotificationRpcService;
import com.bkanent.notification.model.NotificationMessageRequest;
import com.bkanent.notification.model.RobotMessageRequest;
import com.bkanent.notification.service.NotificationManagementService;
import org.apache.dubbo.config.annotation.DubboService;

/**
 * 通知消息 RPC 实现。
 */
@DubboService
public class NotificationRpcServiceImpl implements NotificationRpcService {

    private final NotificationManagementService notificationManagementService;

    public NotificationRpcServiceImpl(NotificationManagementService notificationManagementService) {
        this.notificationManagementService = notificationManagementService;
    }

    @Override
    public void sendStationMessage(Long userId, String title, String content) {
        notificationManagementService.sendStationMessage(new NotificationMessageRequest(
                userId,
                null,
                null,
                title,
                content,
                "SYSTEM"
        ));
    }

    @Override
    public void sendEmailMessage(String receiverAddress, String title, String content) {
        notificationManagementService.sendEmailMessage(new NotificationMessageRequest(
                null,
                receiverAddress,
                null,
                title,
                content,
                "SYSTEM"
        ));
    }

    @Override
    public void sendRobotMessage(String channel, String webhookUrl, String title, String content) {
        notificationManagementService.sendRobotMessage(new RobotMessageRequest(
                channel,
                webhookUrl,
                title,
                content,
                "SYSTEM"
        ));
    }
}
