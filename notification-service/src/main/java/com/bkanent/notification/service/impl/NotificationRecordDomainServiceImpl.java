package com.bkanent.notification.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bkanent.notification.entity.NotificationRecordEntity;
import com.bkanent.notification.mapper.NotificationRecordMapper;
import com.bkanent.notification.service.NotificationRecordDomainService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 通知消息记录领域服务实现。
 */
@Service
public class NotificationRecordDomainServiceImpl
        extends ServiceImpl<NotificationRecordMapper, NotificationRecordEntity>
        implements NotificationRecordDomainService {

    @Override
    public List<NotificationRecordEntity> listUserMessages(Long userId, String channel, String readStatus) {
        LambdaQueryWrapper<NotificationRecordEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(NotificationRecordEntity::getRecipientUserId, userId);
        queryWrapper.eq(StringUtils.hasText(channel), NotificationRecordEntity::getChannel, channel);
        queryWrapper.eq(StringUtils.hasText(readStatus), NotificationRecordEntity::getReadStatus, readStatus);
        queryWrapper.orderByDesc(NotificationRecordEntity::getSendTime)
                .orderByDesc(NotificationRecordEntity::getCreatedAt);
        return list(queryWrapper);
    }

    @Override
    public long countUnreadMessages(Long userId) {
        return count(new LambdaQueryWrapper<NotificationRecordEntity>()
                .eq(NotificationRecordEntity::getRecipientUserId, userId)
                .eq(NotificationRecordEntity::getChannel, "STATION")
                .eq(NotificationRecordEntity::getReadStatus, "UNREAD"));
    }
}
