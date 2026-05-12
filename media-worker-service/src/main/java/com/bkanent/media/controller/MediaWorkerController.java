package com.bkanent.media.controller;

import com.bkanent.common.model.ApiResponse;
import com.bkanent.common.model.HealthStatusDTO;
import com.bkanent.common.model.MediaGenerateTaskRequest;
import com.bkanent.common.model.MediaTaskResultDTO;
import com.bkanent.media.service.MediaGenerationTaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Media generation task controller.
 */
@RestController
@RequestMapping("/media/tasks")
public class MediaWorkerController {

    private final MediaGenerationTaskService mediaGenerationTaskService;

    public MediaWorkerController(MediaGenerationTaskService mediaGenerationTaskService) {
        this.mediaGenerationTaskService = mediaGenerationTaskService;
    }

    @PostMapping
    public ApiResponse<String> submit(@RequestBody MediaGenerateTaskRequest request) {
        return ApiResponse.ok(mediaGenerationTaskService.submitTask(request));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<MediaTaskResultDTO> detail(@PathVariable String taskId) {
        return ApiResponse.ok(mediaGenerationTaskService.getTaskResult(taskId));
    }

    @GetMapping("/health")
    public ApiResponse<HealthStatusDTO> health() {
        return ApiResponse.ok(new HealthStatusDTO("media-worker-service", "UP", "1.0.0-SNAPSHOT"));
    }
}
