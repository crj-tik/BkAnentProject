package com.bkanent.notification.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bkanent.notification.entity.NotificationRecordEntity;

import java.util.List;

/**
 * 通知消息记录领域服务接口。
 */
public interface NotificationRecordDomainService extends IService<NotificationRecordEntity> {

    /**
     * 业务方法：listUserMessages。
     */
    List<NotificationRecordEntity> listUserMessages(Long userId, String channel, String readStatus);

    /**
     * 业务方法：countUnreadMessages。
     */
    long countUnreadMessages(Long userId);
}
