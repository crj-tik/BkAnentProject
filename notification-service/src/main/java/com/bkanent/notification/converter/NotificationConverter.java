package com.bkanent.notification.converter;

import com.bkanent.notification.entity.NotificationRecordEntity;
import com.bkanent.notification.model.NotificationListItemResponse;
import org.springframework.stereotype.Component;

/**
 * 通知消息对象转换器。
 */
@Component
public class NotificationConverter {

    public NotificationListItemResponse toListItem(NotificationRecordEntity entity) {
        return new NotificationListItemResponse(
                entity.getId(),
                entity.getRecipientUserId(),
                entity.getChannel(),
                entity.getSceneCode(),
                entity.getTitle(),
                entity.getContent(),
                entity.getReceiverAddress(),
                entity.getSendStatus(),
                entity.getReadStatus(),
                entity.getReadTime(),
                entity.getSendTime()
        );
    }
}
