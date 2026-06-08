package com.bkanent.notification.mcp;

import com.bkanent.common.tool.McpTool;
import com.bkanent.notification.model.NotificationListItemResponse;
import com.bkanent.notification.model.NotificationMessageRequest;
import com.bkanent.notification.service.NotificationManagementService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class NotificationMcpTools implements McpTool {

    private final NotificationManagementService notificationManagementService;

    public NotificationMcpTools(NotificationManagementService notificationManagementService) {
        this.notificationManagementService = notificationManagementService;
    }

    @Tool(description = "Send an in-app station message to a user. Returns the message ID.")
    public Long sendStationMessage(
            @ToolParam(description = "Recipient user ID") Long userId,
            @ToolParam(description = "Message title") String title,
            @ToolParam(description = "Message content") String content) {
        return notificationManagementService.sendStationMessage(new NotificationMessageRequest(
                userId, null, "AGENT_WORKFLOW", title, content, "notification-agent"));
    }

    @Tool(description = "Send an email notification to a user. Requires receiver email address. Returns the message ID.")
    public Long sendEmailMessage(
            @ToolParam(description = "Recipient user ID") Long userId,
            @ToolParam(description = "Message title") String title,
            @ToolParam(description = "Message content") String content,
            @ToolParam(description = "Receiver email address") String receiverAddress) {
        return notificationManagementService.sendEmailMessage(new NotificationMessageRequest(
                userId, receiverAddress, "AGENT_WORKFLOW", title, content, "notification-agent"));
    }

    @Tool(description = "List messages for a user, optionally filtered by channel and read status.")
    public List<NotificationListItemResponse> listUserMessages(
            @ToolParam(description = "User ID") Long userId,
            @ToolParam(description = "Channel filter: station, email. Pass empty string to skip.") String channel,
            @ToolParam(description = "Read status filter: READ, UNREAD. Pass empty string to skip.") String readStatus) {
        return notificationManagementService.listUserMessages(userId,
                channel == null || channel.isBlank() ? null : channel,
                readStatus == null || readStatus.isBlank() ? null : readStatus);
    }

    @Tool(description = "Count unread messages for a user.")
    public Long countUnreadMessages(
            @ToolParam(description = "User ID") Long userId) {
        return notificationManagementService.countUnreadMessages(userId);
    }
}
