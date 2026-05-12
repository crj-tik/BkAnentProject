package com.bkanent.notification.service;

import com.bkanent.notification.model.NotificationListItemResponse;
import com.bkanent.notification.model.NotificationMessageRequest;
import com.bkanent.notification.model.RobotMessageRequest;

import java.util.List;

/**
 * 通知消息管理服务接口。
 */
public interface NotificationManagementService {

    /**
     * 业务方法：sendStationMessage。
     */
    Long sendStationMessage(NotificationMessageRequest request);

    /**
     * 业务方法：sendEmailMessage。
     */
    Long sendEmailMessage(NotificationMessageRequest request);

    /**
     * 业务方法：sendRobotMessage。
     */
    Long sendRobotMessage(RobotMessageRequest request);

    /**
     * 业务方法：listUserMessages。
     */
    List<NotificationListItemResponse> listUserMessages(Long userId, String channel, String readStatus);

    /**
     * 业务方法：markMessageRead。
     */
    void markMessageRead(Long notificationId, Long userId);

    /**
     * 业务方法：countUnreadMessages。
     */
    long countUnreadMessages(Long userId);
}
