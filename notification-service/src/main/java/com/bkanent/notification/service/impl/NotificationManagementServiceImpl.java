package com.bkanent.notification.service.impl;

import com.bkanent.notification.converter.NotificationConverter;
import com.bkanent.notification.entity.NotificationRecordEntity;
import com.bkanent.notification.enums.NotificationChannelEnum;
import com.bkanent.notification.enums.NotificationReadStatusEnum;
import com.bkanent.notification.enums.NotificationSendStatusEnum;
import com.bkanent.notification.model.NotificationListItemResponse;
import com.bkanent.notification.model.NotificationMessageRequest;
import com.bkanent.notification.model.RobotMessageRequest;
import com.bkanent.notification.service.NotificationManagementService;
import com.bkanent.notification.service.NotificationRecordDomainService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通知消息管理服务实现。
 */
@Service
public class NotificationManagementServiceImpl implements NotificationManagementService {

    private static final Logger log = LoggerFactory.getLogger(NotificationManagementServiceImpl.class);

    private final NotificationRecordDomainService notificationRecordDomainService;
    private final NotificationConverter notificationConverter;

    public NotificationManagementServiceImpl(NotificationRecordDomainService notificationRecordDomainService,
                                             NotificationConverter notificationConverter) {
        this.notificationRecordDomainService = notificationRecordDomainService;
        this.notificationConverter = notificationConverter;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long sendStationMessage(NotificationMessageRequest request) {
        validateStationRequest(request);
        NotificationRecordEntity entity = buildBaseRecord(
                NotificationChannelEnum.STATION.name(),
                request.userId(),
                null,
                null,
                request.sceneCode(),
                request.title(),
                request.content()
        );
        entity.setReadStatus(NotificationReadStatusEnum.UNREAD.name());
        entity.setSendStatus(NotificationSendStatusEnum.SENT.name());
        entity.setSendTime(LocalDateTime.now());
        notificationRecordDomainService.save(entity);
        log.info("站内消息推送成功，userId={}，notificationId={}，标题={}", request.userId(), entity.getId(), request.title());
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long sendEmailMessage(NotificationMessageRequest request) {
        if (!StringUtils.hasText(request.receiverAddress())) {
            throw new IllegalArgumentException("邮件接收地址不能为空");
        }
        NotificationRecordEntity entity = buildBaseRecord(
                NotificationChannelEnum.EMAIL.name(),
                request.userId(),
                request.receiverAddress(),
                null,
                request.sceneCode(),
                request.title(),
                request.content()
        );
        entity.setReadStatus(NotificationReadStatusEnum.READ.name());
        mockSend(entity, "邮件");
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long sendRobotMessage(RobotMessageRequest request) {
        if (!NotificationChannelEnum.contains(request.channel())
                || NotificationChannelEnum.STATION.name().equalsIgnoreCase(request.channel())
                || NotificationChannelEnum.EMAIL.name().equalsIgnoreCase(request.channel())) {
            throw new IllegalArgumentException("机器人渠道不合法: " + request.channel());
        }
        if (!StringUtils.hasText(request.webhookUrl())) {
            throw new IllegalArgumentException("机器人 Webhook 地址不能为空");
        }
        NotificationRecordEntity entity = buildBaseRecord(
                request.channel().toUpperCase(),
                null,
                null,
                request.webhookUrl(),
                "ROBOT_NOTICE",
                request.title(),
                request.content()
        );
        entity.setReadStatus(NotificationReadStatusEnum.READ.name());
        mockSend(entity, "机器人");
        return entity.getId();
    }

    @Override
    public List<NotificationListItemResponse> listUserMessages(Long userId, String channel, String readStatus) {
        if (StringUtils.hasText(readStatus) && !NotificationReadStatusEnum.contains(readStatus)) {
            throw new IllegalArgumentException("消息读取状态不合法: " + readStatus);
        }
        return notificationRecordDomainService.listUserMessages(userId, channel, readStatus).stream()
                .map(notificationConverter::toListItem)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markMessageRead(Long notificationId, Long userId) {
        NotificationRecordEntity entity = notificationRecordDomainService.getById(notificationId);
        if (entity == null) {
            throw new IllegalArgumentException("通知消息不存在: " + notificationId);
        }
        if (!NotificationChannelEnum.STATION.name().equalsIgnoreCase(entity.getChannel())) {
            throw new IllegalArgumentException("只有站内消息支持已读管理");
        }
        if (userId == null || !userId.equals(entity.getRecipientUserId())) {
            throw new IllegalArgumentException("当前用户无权操作该消息");
        }
        entity.setReadStatus(NotificationReadStatusEnum.READ.name());
        entity.setReadTime(LocalDateTime.now());
        notificationRecordDomainService.updateById(entity);
        log.info("站内消息已标记为已读，notificationId={}，userId={}", notificationId, userId);
    }

    @Override
    public long countUnreadMessages(Long userId) {
        return notificationRecordDomainService.countUnreadMessages(userId);
    }

    private void validateStationRequest(NotificationMessageRequest request) {
        if (request.userId() == null) {
            throw new IllegalArgumentException("站内消息接收用户不能为空");
        }
        if (!StringUtils.hasText(request.title())) {
            throw new IllegalArgumentException("通知标题不能为空");
        }
        if (!StringUtils.hasText(request.content())) {
            throw new IllegalArgumentException("通知内容不能为空");
        }
    }

    private NotificationRecordEntity buildBaseRecord(String channel,
                                                     Long userId,
                                                     String receiverAddress,
                                                     String externalWebhook,
                                                     String sceneCode,
                                                     String title,
                                                     String content) {
        NotificationRecordEntity entity = new NotificationRecordEntity();
        entity.setRecipientUserId(userId);
        entity.setChannel(channel);
        entity.setReceiverAddress(receiverAddress);
        entity.setExternalWebhook(externalWebhook);
        entity.setSceneCode(sceneCode);
        entity.setTitle(title);
        entity.setContent(content);
        entity.setSendStatus(NotificationSendStatusEnum.PENDING.name());
        entity.setReadStatus(NotificationReadStatusEnum.UNREAD.name());
        return entity;
    }

    private void mockSend(NotificationRecordEntity entity, String channelName) {
        entity.setSendStatus(NotificationSendStatusEnum.SENT.name());
        entity.setSendTime(LocalDateTime.now());
        entity.setExternalMessageId("MOCK-" + entity.getChannel() + "-" + System.currentTimeMillis());
        notificationRecordDomainService.save(entity);
        log.info("{}消息发送成功，channel={}，notificationId={}，目标={}",
                channelName, entity.getChannel(), entity.getId(),
                StringUtils.hasText(entity.getReceiverAddress()) ? entity.getReceiverAddress() : entity.getExternalWebhook());
    }
}
