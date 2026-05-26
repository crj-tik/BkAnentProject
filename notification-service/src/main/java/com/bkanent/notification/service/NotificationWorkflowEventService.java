package com.bkanent.notification.service;

import com.bkanent.common.agent.SessionStreamEvent;
import com.bkanent.notification.config.NotificationWorkflowEventProperties;
import com.bkanent.notification.model.NotificationMessageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.StringJoiner;

@Service
public class NotificationWorkflowEventService {

    private static final Logger log = LoggerFactory.getLogger(NotificationWorkflowEventService.class);

    private final NotificationManagementService notificationManagementService;
    private final NotificationEventConsumeService notificationEventConsumeService;
    private final NotificationMetricsService notificationMetricsService;
    private final NotificationWorkflowEventProperties notificationWorkflowEventProperties;

    public NotificationWorkflowEventService(NotificationManagementService notificationManagementService,
                                            NotificationEventConsumeService notificationEventConsumeService,
                                            NotificationMetricsService notificationMetricsService,
                                            NotificationWorkflowEventProperties notificationWorkflowEventProperties) {
        this.notificationManagementService = notificationManagementService;
        this.notificationEventConsumeService = notificationEventConsumeService;
        this.notificationMetricsService = notificationMetricsService;
        this.notificationWorkflowEventProperties = notificationWorkflowEventProperties;
    }

    public void consume(SessionStreamEvent event) {
        if (event == null || event.metadata() == null || !supports(event.eventType())) {
            return;
        }
        Long userId = asLong(event.metadata().get("userId"));
        if (userId == null || !shouldNotify(event)) {
            return;
        }
        String dedupeKey = buildDedupeKey(event, userId);
        NotificationConsumeStartResult startResult = notificationEventConsumeService.tryStartConsume(
                dedupeKey,
                event.eventType(),
                event.taskId(),
                event.traceId(),
                userId,
                notificationWorkflowEventProperties.getMaxAttempts()
        );
        if (!startResult.accepted()) {
            notificationMetricsService.recordConsumeResult(startResult.deadLettered() ? "DEAD_LETTER_SKIPPED" : "DUPLICATE_SKIPPED");
            log.info("skip duplicate workflow event notification, eventType={}, taskId={}, dedupeKey={}",
                    event.eventType(), event.taskId(), dedupeKey);
            return;
        }
        long startedAt = System.currentTimeMillis();
        try {
            Long notificationId = notificationManagementService.sendStationMessage(new NotificationMessageRequest(
                    userId,
                    null,
                    "AGENT_WORKFLOW_EVENT",
                    buildTitle(event),
                    buildContent(event),
                    "notification-workflow-consumer"
            ));
            notificationEventConsumeService.markConsumed(dedupeKey, notificationId);
            notificationMetricsService.recordConsumeResult("CONSUMED");
            notificationMetricsService.recordWorkflowEvent(event.eventType(), "CONSUMED", System.currentTimeMillis() - startedAt);
            log.info("workflow event notification sent, eventType={}, taskId={}, notificationId={}",
                    event.eventType(), event.taskId(), notificationId);
        } catch (RuntimeException ex) {
            String errorMessage = trimErrorMessage(ex.getMessage());
            if (startResult.attemptCount() >= notificationWorkflowEventProperties.getMaxAttempts()) {
                notificationEventConsumeService.markDeadLetter(dedupeKey, errorMessage);
                notificationMetricsService.recordConsumeResult("DEAD_LETTER");
                notificationMetricsService.recordWorkflowEvent(event.eventType(), "DEAD_LETTER", System.currentTimeMillis() - startedAt);
                log.warn("workflow event moved to dead letter, eventType={}, taskId={}, dedupeKey={}",
                        event.eventType(), event.taskId(), dedupeKey, ex);
                return;
            }
            notificationEventConsumeService.markFailed(dedupeKey, errorMessage);
            notificationMetricsService.recordConsumeResult("FAILED");
            notificationMetricsService.recordWorkflowEvent(event.eventType(), "FAILED", System.currentTimeMillis() - startedAt);
            throw ex;
        }
    }

    private boolean supports(String eventType) {
        return "task.waiting_approval".equals(eventType)
                || "task.approval_approved".equals(eventType)
                || "task.approval_rejected".equals(eventType)
                || "task.approval_terminated".equals(eventType)
                || "task.completed".equals(eventType)
                || "task.failed".equals(eventType)
                || "handoff.completed".equals(eventType)
                || "artifact.created".equals(eventType)
                || "publish.prepared".equals(eventType)
                || "publish.completed".equals(eventType)
                || "notification.requested".equals(eventType);
    }

    private boolean shouldNotify(SessionStreamEvent event) {
        if ("handoff.completed".equals(event.eventType())) {
            String nextIntent = text(event.metadata().get("nextIntent"));
            return "marketing.publish_prepare".equalsIgnoreCase(nextIntent)
                    || "marketing.publish".equalsIgnoreCase(nextIntent)
                    || "notification.send".equalsIgnoreCase(nextIntent)
                    || "settlement.prepare".equalsIgnoreCase(nextIntent)
                    || "settlement.batch".equalsIgnoreCase(nextIntent);
        }
        if ("artifact.created".equals(event.eventType())) {
            String artifactType = text(event.metadata().get("artifactType"));
            return "publish_payload".equalsIgnoreCase(artifactType)
                    || "publish_payload_body".equalsIgnoreCase(artifactType)
                    || "media_task_detail".equalsIgnoreCase(artifactType)
                    || "parallel_result".equalsIgnoreCase(artifactType);
        }
        return true;
    }

    private String buildTitle(SessionStreamEvent event) {
        return switch (event.eventType()) {
            case "task.waiting_approval" -> "审批待处理";
            case "task.approval_approved" -> "审批已通过";
            case "task.approval_rejected" -> "审批已驳回";
            case "task.approval_terminated" -> "审批已终止";
            case "task.completed" -> "工作流已完成";
            case "task.failed" -> "工作流执行失败";
            case "handoff.completed" -> "流程进入下一阶段";
            case "artifact.created" -> "关键产物已生成";
            case "publish.prepared" -> "发布准备已完成";
            case "publish.completed" -> "内容已发布";
            case "notification.requested" -> "通知任务已创建";
            default -> "工作流通知";
        };
    }

    private String buildContent(SessionStreamEvent event) {
        StringBuilder builder = new StringBuilder();
        builder.append("taskId=").append(event.taskId());
        if (StringUtils.hasText(event.content())) {
            builder.append(", content=").append(event.content());
        }
        appendIfPresent(builder, event.metadata(), "approvalId", ", approvalId=");
        appendIfPresent(builder, event.metadata(), "status", ", status=");
        appendIfPresent(builder, event.metadata(), "approvalType", ", approvalType=");
        appendIfPresent(builder, event.metadata(), "nextIntent", ", nextIntent=");
        appendIfPresent(builder, event.metadata(), "artifactType", ", artifactType=");
        appendIfPresent(builder, event.metadata(), "contentId", ", contentId=");
        appendIfPresent(builder, event.metadata(), "publishStatus", ", publishStatus=");
        appendIfPresent(builder, event.metadata(), "externalPublishId", ", externalPublishId=");
        return builder.toString();
    }

    private String buildDedupeKey(SessionStreamEvent event, Long userId) {
        StringJoiner joiner = new StringJoiner("|");
        joiner.add(defaultText(event.eventType()))
                .add(defaultText(event.taskId()))
                .add(defaultText(event.traceId()))
                .add(String.valueOf(userId));
        appendKeyPart(joiner, event.metadata(), "approvalId");
        appendKeyPart(joiner, event.metadata(), "status");
        appendKeyPart(joiner, event.metadata(), "nextIntent");
        appendKeyPart(joiner, event.metadata(), "artifactId");
        appendKeyPart(joiner, event.metadata(), "artifactType");
        appendKeyPart(joiner, event.metadata(), "contentId");
        appendKeyPart(joiner, event.metadata(), "publishStatus");
        appendKeyPart(joiner, event.metadata(), "externalPublishId");
        return joiner.toString();
    }

    private void appendKeyPart(StringJoiner joiner, Map<String, Object> metadata, String key) {
        String value = text(metadata.get(key));
        if (value != null) {
            joiner.add(key + "=" + value);
        }
    }

    private void appendIfPresent(StringBuilder builder, Map<String, Object> metadata, String key, String prefix) {
        String value = text(metadata.get(key));
        if (value != null) {
            builder.append(prefix).append(value);
        }
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text : null;
    }

    private String defaultText(String value) {
        return value == null ? "" : value;
    }

    private String trimErrorMessage(String errorMessage) {
        if (errorMessage == null) {
            return null;
        }
        return errorMessage.length() > 250 ? errorMessage.substring(0, 250) : errorMessage;
    }
}
