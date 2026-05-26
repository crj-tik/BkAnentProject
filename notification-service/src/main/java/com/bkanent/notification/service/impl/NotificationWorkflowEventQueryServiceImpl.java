package com.bkanent.notification.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bkanent.notification.entity.NotificationEventConsumeEntity;
import com.bkanent.notification.model.NotificationWorkflowEventConsumeResponse;
import com.bkanent.notification.service.NotificationWorkflowEventQueryService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class NotificationWorkflowEventQueryServiceImpl implements NotificationWorkflowEventQueryService {

    private final NotificationEventConsumeServiceImpl notificationEventConsumeService;

    public NotificationWorkflowEventQueryServiceImpl(NotificationEventConsumeServiceImpl notificationEventConsumeService) {
        this.notificationEventConsumeService = notificationEventConsumeService;
    }

    @Override
    public List<NotificationWorkflowEventConsumeResponse> listRecent(String consumeStatus,
                                                                     String eventType,
                                                                     String taskId,
                                                                     Long recipientUserId,
                                                                     Integer limit) {
        int size = limit == null || limit <= 0 ? 20 : Math.min(limit, 100);
        LambdaQueryWrapper<NotificationEventConsumeEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StringUtils.hasText(consumeStatus), NotificationEventConsumeEntity::getConsumeStatus, consumeStatus);
        wrapper.eq(StringUtils.hasText(eventType), NotificationEventConsumeEntity::getEventType, eventType);
        wrapper.eq(StringUtils.hasText(taskId), NotificationEventConsumeEntity::getTaskId, taskId);
        wrapper.eq(recipientUserId != null, NotificationEventConsumeEntity::getRecipientUserId, recipientUserId);
        wrapper.orderByDesc(NotificationEventConsumeEntity::getUpdatedAt)
                .orderByDesc(NotificationEventConsumeEntity::getCreatedAt)
                .last("limit " + size);
        return notificationEventConsumeService.list(wrapper).stream()
                .map(entity -> new NotificationWorkflowEventConsumeResponse(
                        entity.getId(),
                        entity.getDedupeKey(),
                        entity.getEventType(),
                        entity.getTaskId(),
                        entity.getTraceId(),
                        entity.getRecipientUserId(),
                        entity.getConsumeStatus(),
                        entity.getAttemptCount(),
                        entity.getNotificationId(),
                        entity.getErrorMessage(),
                        entity.getConsumedAt(),
                        entity.getCreatedAt(),
                        entity.getUpdatedAt()
                ))
                .toList();
    }
}
