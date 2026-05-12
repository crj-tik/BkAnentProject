package com.bkanent.media.service.impl;

import com.bkanent.common.model.MediaGenerateTaskRequest;
import com.bkanent.common.model.MediaTaskResultDTO;
import com.bkanent.media.model.MediaTaskStatusEnum;
import com.bkanent.media.service.MediaTaskStoreService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 媒体任务结果存储服务实现。
 */
@Service
public class MediaTaskStoreServiceImpl implements MediaTaskStoreService {

    private final Map<String, MediaTaskResultDTO> taskStore = new ConcurrentHashMap<>();

    @Override
    public MediaTaskResultDTO createQueuedTask(String taskId, MediaGenerateTaskRequest request) {
        MediaTaskResultDTO result = new MediaTaskResultDTO(
                taskId,
                request.listingId(),
                request.contentId(),
                request.callerService(),
                request.taskType(),
                MediaTaskStatusEnum.QUEUED.name(),
                List.of(),
                null,
                null,
                "任务已发送到 RocketMQ，等待 Worker 消费",
                LocalDateTime.now(),
                null
        );
        taskStore.put(taskId, result);
        return result;
    }

    @Override
    public MediaTaskResultDTO markRunning(String taskId) {
        return updateTaskResult(taskId, MediaTaskStatusEnum.RUNNING, List.of(), null, null, "Worker 正在处理消息", null);
    }

    @Override
    public MediaTaskResultDTO markSuccess(String taskId, List<String> assetUrls, String coverImageUrl, String videoUrl, String message) {
        return updateTaskResult(taskId, MediaTaskStatusEnum.SUCCESS, assetUrls, coverImageUrl, videoUrl, message, LocalDateTime.now());
    }

    @Override
    public MediaTaskResultDTO markFailed(String taskId, String message) {
        return updateTaskResult(taskId, MediaTaskStatusEnum.FAILED, List.of(), null, null, message, LocalDateTime.now());
    }

    @Override
    public MediaTaskResultDTO getTaskResult(String taskId) {
        MediaTaskResultDTO result = taskStore.get(taskId);
        if (result == null) {
            throw new IllegalArgumentException("媒体任务不存在: " + taskId);
        }
        return result;
    }

    @Override
    public MediaTaskResultDTO updateTaskResult(String taskId,
                                               MediaTaskStatusEnum status,
                                               List<String> assetUrls,
                                               String coverImageUrl,
                                               String videoUrl,
                                               String message,
                                               LocalDateTime finishedAt) {
        MediaTaskResultDTO current = getTaskResult(taskId);
        MediaTaskResultDTO updated = new MediaTaskResultDTO(
                current.taskId(),
                current.listingId(),
                current.contentId(),
                current.callerService(),
                current.taskType(),
                status.name(),
                assetUrls,
                coverImageUrl,
                videoUrl,
                message,
                current.createdAt(),
                finishedAt
        );
        taskStore.put(taskId, updated);
        return updated;
    }
}
