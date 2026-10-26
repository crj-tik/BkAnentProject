package com.bkanent.media.service;

import com.bkanent.common.model.MediaGenerateTaskRequest;
import com.bkanent.common.model.MediaTaskResultDTO;
import com.bkanent.media.model.MediaBackgroundPermit;
import com.bkanent.media.model.MediaTaskContext;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 媒体任务结果存储服务接口。
 */
public interface MediaTaskStoreService {

    /**
     * 业务方法：createQueuedTask。
     */
    MediaTaskResultDTO createQueuedTask(String taskId, MediaGenerateTaskRequest request,
                                        MediaBackgroundPermit permit);

    /**
     * 业务方法：markSuccess。
     */
    MediaTaskResultDTO markSuccess(String taskId, List<String> assetUrls, String coverImageUrl, String videoUrl, String message);

    /**
     * 业务方法：markFailed。
     */
    MediaTaskResultDTO markFailed(String taskId, String message);

    MediaTaskResultDTO getTaskResult(String taskId, String ownerActorId, boolean businessWide);

    MediaTaskContext findTaskContext(String taskId);

    MediaTaskContext claimQueuedTask(String taskId, MediaBackgroundPermit permit);
}
