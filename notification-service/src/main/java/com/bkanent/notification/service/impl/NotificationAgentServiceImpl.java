package com.bkanent.notification.service.impl;

import com.bkanent.common.agent.AgentCard;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.bkanent.notification.model.NotificationMessageRequest;
import com.bkanent.notification.service.NotificationAgentService;
import com.bkanent.notification.service.NotificationManagementService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class NotificationAgentServiceImpl implements NotificationAgentService {

    private final NotificationManagementService notificationManagementService;

    public NotificationAgentServiceImpl(NotificationManagementService notificationManagementService) {
        this.notificationManagementService = notificationManagementService;
    }

    @Override
    public AgentCard getAgentCard() {
        return new AgentCard(
                "notification-agent",
                "Notification Agent",
                "Responsible for station, email and robot notification delivery",
                "1.0.0",
                List.of("notification-send", "notification-station"),
                List.of("notification"),
                true,
                true,
                "/a2a",
                List.of("text", "json"),
                List.of("text", "json")
        );
    }

    @Override
    public AgentTaskInvokeResponse invoke(AgentTaskInvokeRequest request) {
        Map<String, Object> context = request.structuredContext() == null ? Map.of() : request.structuredContext();
        String channel = text(context.get("notificationChannel"), "station");
        Long messageId = switch (channel.toLowerCase()) {
            case "email" -> notificationManagementService.sendEmailMessage(buildMessageRequest(context));
            default -> notificationManagementService.sendStationMessage(buildMessageRequest(context));
        };

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("resultType", "notification_result");
        output.put("contentType", "notification_summary");
        output.put("notificationChannel", channel);
        output.put("notificationId", messageId);
        output.put("deliveryStatus", "SENT");
        output.put("summary", "Notification sent via " + channel + " with id " + messageId);

        return new AgentTaskInvokeResponse(
                request.sessionId(),
                request.taskId(),
                "notification-agent",
                "COMPLETED",
                output,
                List.of(),
                List.of(),
                String.valueOf(output.get("summary")),
                request.traceId()
        );
    }

    private NotificationMessageRequest buildMessageRequest(Map<String, Object> context) {
        return new NotificationMessageRequest(
                asLong(context.get("notifyUserId")),
                text(context.get("receiverAddress"), null),
                text(context.get("sceneCode"), "AGENT_WORKFLOW"),
                text(context.get("title"), "Workflow Notification"),
                text(context.get("content"), "A workflow event requires your attention."),
                text(context.get("operator"), "supervisor-agent")
        );
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String text(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? defaultValue : text;
    }
}
