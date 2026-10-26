package com.bkanent.notification.service.impl;

import com.bkanent.common.a2a.VerifiedDomainAuthorization;
import com.bkanent.common.agent.SessionStreamEvent;
import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.notification.converter.NotificationConverter;
import com.bkanent.notification.config.NotificationIntegrationProperties;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 通知消息管理服务实现。
 */
@Service
public class NotificationManagementServiceImpl implements NotificationManagementService {

    private static final Set<String> WORKFLOW_EVENTS = Set.of("task.waiting_approval", "task.approval_approved",
            "task.approval_rejected", "task.approval_terminated", "task.completed", "task.failed",
            "handoff.completed", "artifact.created", "publish.prepared", "publish.completed",
            "notification.requested");

    private static final Logger log = LoggerFactory.getLogger(NotificationManagementServiceImpl.class);

    private final NotificationRecordDomainService notificationRecordDomainService;
    private final NotificationConverter notificationConverter;
    private final NotificationIntegrationProperties integrationProperties;

    public NotificationManagementServiceImpl(NotificationRecordDomainService notificationRecordDomainService,
                                             NotificationConverter notificationConverter,
                                             NotificationIntegrationProperties integrationProperties) {
        this.notificationRecordDomainService = notificationRecordDomainService;
        this.notificationConverter = notificationConverter;
        this.integrationProperties = integrationProperties;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long sendStationMessage(NotificationMessageRequest request) {
        validateStationRequest(request);
        requireStationRecipient(request.userId());
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
    public Long sendWorkflowEventMessage(SessionStreamEvent event, String title, String content) {
        Long recipientUserId = workflowEventRecipient(event);
        if (!StringUtils.hasText(title) || !StringUtils.hasText(content)) {
            throw new IllegalArgumentException("通知标题和内容不能为空");
        }
        NotificationRecordEntity entity = buildBaseRecord(NotificationChannelEnum.STATION.name(), recipientUserId,
                null, null, "AGENT_WORKFLOW_EVENT", title, content);
        entity.setReadStatus(NotificationReadStatusEnum.UNREAD.name());
        entity.setSendStatus(NotificationSendStatusEnum.SENT.name());
        entity.setSendTime(LocalDateTime.now());
        notificationRecordDomainService.save(entity);
        log.info("工作流事件通知已记录，eventType={}，taskId={}，notificationId={}",
                event.eventType(), event.taskId(), entity.getId());
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long sendEmailMessage(NotificationMessageRequest request) {
        VerifiedDomainAuthorization.requirePermission("notification.email.send.all");
        requireLocalSimulationMode("email");
        if (request == null || request.userId() == null || request.userId() <= 0
                || !StringUtils.hasText(request.receiverAddress())) {
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
        VerifiedDomainAuthorization.requirePermission("notification.robot.send.all");
        requireLocalSimulationMode("robot");
        if (request == null || !NotificationChannelEnum.contains(request.channel())
                || NotificationChannelEnum.STATION.name().equalsIgnoreCase(request.channel())
                || NotificationChannelEnum.EMAIL.name().equalsIgnoreCase(request.channel())) {
            throw new IllegalArgumentException("机器人渠道不合法");
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
        long actorId = requireCurrentUser("notification.message.read.own", userId);
        if (StringUtils.hasText(readStatus) && !NotificationReadStatusEnum.contains(readStatus)) {
            throw new IllegalArgumentException("消息读取状态不合法: " + readStatus);
        }
        return notificationRecordDomainService.listUserMessages(actorId, channel, readStatus).stream()
                .map(notificationConverter::toListItem)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markMessageRead(Long notificationId, Long userId) {
        long actorId = requireCurrentUser("notification.message.mark-read.own", userId);
        NotificationRecordEntity entity = notificationRecordDomainService.getById(notificationId);
        if (entity == null) {
            throw new IllegalArgumentException("通知消息不存在: " + notificationId);
        }
        if (!NotificationChannelEnum.STATION.name().equalsIgnoreCase(entity.getChannel())) {
            throw new IllegalArgumentException("只有站内消息支持已读管理");
        }
        if (!Long.valueOf(actorId).equals(entity.getRecipientUserId())) {
            throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
        }
        entity.setReadStatus(NotificationReadStatusEnum.READ.name());
        entity.setReadTime(LocalDateTime.now());
        notificationRecordDomainService.updateById(entity);
        log.info("站内消息已标记为已读，notificationId={}，actorId={}", notificationId, actorId);
    }

    @Override
    public long countUnreadMessages(Long userId) {
        long actorId = requireCurrentUser("notification.message.read.own", userId);
        return notificationRecordDomainService.countUnreadMessages(actorId);
    }

    private void requireStationRecipient(Long recipientUserId) {
        long actorId = VerifiedDomainAuthorization.requireActorId();
        if (recipientUserId == null || recipientUserId <= 0) {
            throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
        }
        if (recipientUserId == actorId && VerifiedDomainAuthorization.hasOperation("notification.message.send.own")) {
            VerifiedDomainAuthorization.requirePermission("notification.message.send.own");
        } else {
            VerifiedDomainAuthorization.requirePermission("notification.message.send.all");
        }
    }

    private long requireCurrentUser(String operation, Long claimedUserId) {
        VerifiedDomainAuthorization.requirePermission(operation);
        long actorId = VerifiedDomainAuthorization.requireActorId();
        if (claimedUserId != null && claimedUserId != actorId) {
            throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
        }
        return actorId;
    }

    private Long workflowEventRecipient(SessionStreamEvent event) {
        if (event == null || event.metadata() == null || event.sessionId() == null || event.sessionId().isBlank()
                || event.taskId() == null || event.taskId().isBlank() || !WORKFLOW_EVENTS.contains(event.eventType())) {
            throw new AuthzException(AuthzErrorCode.AUTHENTICATION_FAILED);
        }
        Long userId = parseLong(event.metadata().get("userId"));
        if (userId == null || userId <= 0) throw new AuthzException(AuthzErrorCode.AUTHENTICATION_FAILED);
        Object runId = event.metadata().get("runId");
        if (runId != null && !event.taskId().equals(String.valueOf(runId))) {
            throw new AuthzException(AuthzErrorCode.CONTEXT_CONFLICT);
        }
        return userId;
    }

    private Long parseLong(Object value) {
        if (!(value instanceof Number) && !(value instanceof String)) return null;
        String text = String.valueOf(value);
        if (!StringUtils.hasText(text)) return null;
        try {
            return new BigDecimal(text).longValueExact();
        } catch (NumberFormatException | ArithmeticException ignored) {
            return null;
        }
    }

    private void validateStationRequest(NotificationMessageRequest request) {
        if (request == null || request.userId() == null || request.userId() <= 0) {
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
        entity.setExternalMessageId("SIMULATED-" + entity.getChannel() + "-" + System.currentTimeMillis());
        notificationRecordDomainService.save(entity);
        log.info("{}模拟消息已记录，channel={}，notificationId={}，目标={}",
                channelName, entity.getChannel(), entity.getId(),
                StringUtils.hasText(entity.getReceiverAddress()) ? entity.getReceiverAddress() : entity.getExternalWebhook());
    }

    private void requireLocalSimulationMode(String channel) {
        if (!integrationProperties.isLocalMode()) {
            throw new IllegalStateException("notification " + channel
                    + " provider is not configured; set notification.integration.mode=local only for simulation");
        }
    }
}
