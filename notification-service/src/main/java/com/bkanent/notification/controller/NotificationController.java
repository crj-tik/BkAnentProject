package com.bkanent.notification.controller;

import com.bkanent.common.model.ApiResponse;
import com.bkanent.common.model.HealthStatusDTO;
import com.bkanent.notification.model.NotificationListItemResponse;
import com.bkanent.notification.model.NotificationMessageRequest;
import com.bkanent.notification.model.NotificationReadRequest;
import com.bkanent.notification.model.NotificationWorkflowEventConsumeResponse;
import com.bkanent.notification.model.RobotMessageRequest;
import com.bkanent.notification.service.NotificationManagementService;
import com.bkanent.notification.service.NotificationWorkflowEventQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Notification delivery controller.
 */
@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationManagementService notificationManagementService;
    private final NotificationWorkflowEventQueryService notificationWorkflowEventQueryService;

    public NotificationController(NotificationManagementService notificationManagementService,
                                  NotificationWorkflowEventQueryService notificationWorkflowEventQueryService) {
        this.notificationManagementService = notificationManagementService;
        this.notificationWorkflowEventQueryService = notificationWorkflowEventQueryService;
    }

    @PostMapping("/station")
    public ApiResponse<Long> station(@RequestBody NotificationMessageRequest request) {
        return ApiResponse.ok(notificationManagementService.sendStationMessage(request));
    }

    @PostMapping("/email")
    public ApiResponse<Long> email(@RequestBody NotificationMessageRequest request) {
        return ApiResponse.ok(notificationManagementService.sendEmailMessage(request));
    }

    @PostMapping("/robot")
    public ApiResponse<Long> robot(@RequestBody RobotMessageRequest request) {
        return ApiResponse.ok(notificationManagementService.sendRobotMessage(request));
    }

    @GetMapping
    public ApiResponse<List<NotificationListItemResponse>> list(@RequestParam Long userId,
                                                                @RequestParam(required = false) String channel,
                                                                @RequestParam(required = false) String readStatus) {
        return ApiResponse.ok(notificationManagementService.listUserMessages(userId, channel, readStatus));
    }

    @PostMapping("/{id}/read")
    public ApiResponse<Void> markRead(@PathVariable Long id, @RequestBody NotificationReadRequest request) {
        notificationManagementService.markMessageRead(id, request.userId());
        return ApiResponse.ok(null);
    }

    @GetMapping("/unread-count")
    public ApiResponse<Long> unreadCount(@RequestParam Long userId) {
        return ApiResponse.ok(notificationManagementService.countUnreadMessages(userId));
    }

    @GetMapping("/workflow-events")
    public ApiResponse<List<NotificationWorkflowEventConsumeResponse>> workflowEvents(
            @RequestParam(required = false) String consumeStatus,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String taskId,
            @RequestParam(required = false) Long recipientUserId,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(notificationWorkflowEventQueryService.listRecent(
                consumeStatus,
                eventType,
                taskId,
                recipientUserId,
                limit
        ));
    }

    @GetMapping("/health")
    public ApiResponse<HealthStatusDTO> health() {
        return ApiResponse.ok(new HealthStatusDTO("notification-service", "UP", "1.0.0-SNAPSHOT"));
    }
}
