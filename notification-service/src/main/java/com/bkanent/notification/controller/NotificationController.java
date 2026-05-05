package com.bkanent.notification.controller;

import com.bkanent.common.model.ApiResponse;
import com.bkanent.common.model.HealthStatusDTO;
import com.bkanent.common.model.NotificationMessageDTO;
import com.bkanent.common.rpc.NotificationRpcService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationRpcService notificationRpcService;

    public NotificationController(NotificationRpcService notificationRpcService) {
        this.notificationRpcService = notificationRpcService;
    }

    @PostMapping("/station")
    public ApiResponse<Void> station(@RequestBody NotificationMessageDTO request) {
        notificationRpcService.sendStationMessage(request.userId(), request.title(), request.content());
        return ApiResponse.ok(null);
    }

    @GetMapping("/health")
    public ApiResponse<HealthStatusDTO> health() {
        return ApiResponse.ok(new HealthStatusDTO("notification-service", "UP", "1.0.0-SNAPSHOT"));
    }
}
