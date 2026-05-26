package com.bkanent.notification.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bkanent.notification.entity.NotificationEventConsumeEntity;
import com.bkanent.notification.mapper.NotificationEventConsumeMapper;
import com.bkanent.notification.service.NotificationConsumeStartResult;
import com.bkanent.notification.service.NotificationEventConsumeService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class NotificationEventConsumeServiceImpl
        extends ServiceImpl<NotificationEventConsumeMapper, NotificationEventConsumeEntity>
        implements NotificationEventConsumeService {

    @Override
    @Transactional(rollbackFor = Exception.class)
    public NotificationConsumeStartResult tryStartConsume(String dedupeKey,
                                                          String eventType,
                                                          String taskId,
                                                          String traceId,
                                                          Long recipientUserId,
                                                          int maxAttempts) {
        if (dedupeKey == null || dedupeKey.isBlank()) {
            return new NotificationConsumeStartResult(true, false, 1, "PROCESSING");
        }
        NotificationEventConsumeEntity existed = selectByDedupeKey(dedupeKey);
        if (existed != null) {
            if ("CONSUMED".equalsIgnoreCase(existed.getConsumeStatus())) {
                return new NotificationConsumeStartResult(false, false,
                        existed.getAttemptCount() == null ? 0 : existed.getAttemptCount(),
                        existed.getConsumeStatus());
            }
            if ("DEAD_LETTER".equalsIgnoreCase(existed.getConsumeStatus())
                    || (existed.getAttemptCount() != null && existed.getAttemptCount() >= maxAttempts)) {
                existed.setConsumeStatus("DEAD_LETTER");
                baseMapper.updateById(existed);
                return new NotificationConsumeStartResult(false, true,
                        existed.getAttemptCount() == null ? maxAttempts : existed.getAttemptCount(),
                        existed.getConsumeStatus());
            }
            int nextAttempt = (existed.getAttemptCount() == null ? 0 : existed.getAttemptCount()) + 1;
            existed.setConsumeStatus("PROCESSING");
            existed.setAttemptCount(nextAttempt);
            existed.setErrorMessage(null);
            existed.setConsumedAt(null);
            baseMapper.updateById(existed);
            return new NotificationConsumeStartResult(true, false, nextAttempt, "PROCESSING");
        }
        NotificationEventConsumeEntity entity = new NotificationEventConsumeEntity();
        entity.setDedupeKey(dedupeKey);
        entity.setEventType(eventType);
        entity.setTaskId(taskId);
        entity.setTraceId(traceId);
        entity.setRecipientUserId(recipientUserId);
        entity.setConsumeStatus("PROCESSING");
        entity.setAttemptCount(1);
        try {
            baseMapper.insert(entity);
            return new NotificationConsumeStartResult(true, false, 1, "PROCESSING");
        } catch (DuplicateKeyException ignored) {
            return new NotificationConsumeStartResult(false, false, 1, "PROCESSING");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markConsumed(String dedupeKey, Long notificationId) {
        NotificationEventConsumeEntity existed = selectByDedupeKey(dedupeKey);
        if (existed == null) {
            return;
        }
        existed.setConsumeStatus("CONSUMED");
        existed.setNotificationId(notificationId);
        existed.setConsumedAt(LocalDateTime.now());
        existed.setErrorMessage(null);
        baseMapper.updateById(existed);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markFailed(String dedupeKey, String errorMessage) {
        NotificationEventConsumeEntity existed = selectByDedupeKey(dedupeKey);
        if (existed == null) {
            return;
        }
        existed.setConsumeStatus("FAILED");
        existed.setErrorMessage(errorMessage);
        baseMapper.updateById(existed);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markDeadLetter(String dedupeKey, String errorMessage) {
        NotificationEventConsumeEntity existed = selectByDedupeKey(dedupeKey);
        if (existed == null) {
            return;
        }
        existed.setConsumeStatus("DEAD_LETTER");
        existed.setErrorMessage(errorMessage);
        baseMapper.updateById(existed);
    }

    private NotificationEventConsumeEntity selectByDedupeKey(String dedupeKey) {
        return baseMapper.selectOne(
                new LambdaQueryWrapper<NotificationEventConsumeEntity>()
                        .eq(NotificationEventConsumeEntity::getDedupeKey, dedupeKey)
                        .last("limit 1")
        );
    }
}
