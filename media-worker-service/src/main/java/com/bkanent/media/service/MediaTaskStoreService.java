package com.bkanent.media.service;

import com.bkanent.common.model.MediaGenerateTaskRequest;
import com.bkanent.common.model.MediaTaskResultDTO;
import com.bkanent.media.model.MediaTaskStatusEnum;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 媒体任务结果存储服务接口。
 */
public interface MediaTaskStoreService {

    /**
     * 业务方法：createQueuedTask。
     */
    MediaTaskResultDTO createQueuedTask(String taskId, MediaGenerateTaskRequest request);

    /**
     * 业务方法：markRunning。
     */
    MediaTaskResultDTO markRunning(String taskId);

    /**
     * 业务方法：markSuccess。
     */
    MediaTaskResultDTO markSuccess(String taskId, List<String> assetUrls, String coverImageUrl, String videoUrl, String message);

    /**
     * 业务方法：markFailed。
     */
    MediaTaskResultDTO markFailed(String taskId, String message);

    /**
     * 业务方法：getTaskResult。
     */
    MediaTaskResultDTO getTaskResult(String taskId);

    MediaTaskResultDTO updateTaskResult(String taskId,
                                        MediaTaskStatusEnum status,
                                        List<String> assetUrls,
                                        String coverImageUrl,
                                        String videoUrl,
                                        String message,
                                        LocalDateTime finishedAt);
}
