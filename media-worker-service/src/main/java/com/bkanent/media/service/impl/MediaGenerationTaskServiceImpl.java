package com.bkanent.media.service.impl;

import com.bkanent.common.model.MediaGenerateTaskRequest;
import com.bkanent.common.model.MediaTaskResultDTO;
import com.bkanent.media.config.MediaRocketMqProperties;
import com.bkanent.media.config.MediaTaskProperties;
import com.bkanent.media.model.MediaGenerateTaskMessage;
import com.bkanent.media.model.MediaTaskStatusEnum;
import com.bkanent.media.service.MediaGenerationTaskService;
import com.bkanent.media.service.MediaTaskStoreService;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 媒体生成任务服务实现。
 */
@Service
public class MediaGenerationTaskServiceImpl implements MediaGenerationTaskService {

    private final RocketMQTemplate rocketMQTemplate;
    private final MediaRocketMqProperties mediaRocketMqProperties;
    private final MediaTaskProperties mediaTaskProperties;
    private final MediaTaskStoreService mediaTaskStoreService;

    public MediaGenerationTaskServiceImpl(RocketMQTemplate rocketMQTemplate,
                                          MediaRocketMqProperties mediaRocketMqProperties,
                                          MediaTaskProperties mediaTaskProperties,
                                          MediaTaskStoreService mediaTaskStoreService) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.mediaRocketMqProperties = mediaRocketMqProperties;
        this.mediaTaskProperties = mediaTaskProperties;
        this.mediaTaskStoreService = mediaTaskStoreService;
    }

    @Override
    public List<String> generateAssetsSync(Long listingId, String prompt) {
        MediaGenerateTaskRequest request = new MediaGenerateTaskRequest(
                listingId,
                null,
                "agent-service",
                "LISTING_IMAGE",
                prompt,
                mediaTaskProperties.getDefaultAngles(),
                mediaTaskProperties.getDefaultAngles().size()
        );
        String taskId = submitTask(request);
        long deadline = System.currentTimeMillis() + mediaTaskProperties.getSyncWaitTimeoutMs();
        while (System.currentTimeMillis() < deadline) {
            MediaTaskResultDTO result = getTaskResult(taskId);
            if (MediaTaskStatusEnum.SUCCESS.name().equals(result.status())) {
                return result.assetUrls();
            }
            if (MediaTaskStatusEnum.FAILED.name().equals(result.status())) {
                throw new IllegalStateException("同步生成房源素材失败: " + result.message());
            }
            sleepQuietly(mediaTaskProperties.getSyncPollIntervalMs());
        }
        throw new IllegalStateException("同步生成房源素材超时，taskId=" + taskId);
    }

    @Override
    public String submitTask(MediaGenerateTaskRequest request) {
        validateRequest(request);
        String taskId = UUID.randomUUID().toString().replace("-", "");
        MediaGenerateTaskRequest normalizedRequest = normalizeRequest(request);
        mediaTaskStoreService.createQueuedTask(taskId, normalizedRequest);
        rocketMQTemplate.convertAndSend(
                mediaRocketMqProperties.getGenerateTopic(),
                new MediaGenerateTaskMessage(taskId, normalizedRequest)
        );
        return taskId;
    }

    @Override
    public MediaTaskResultDTO getTaskResult(String taskId) {
        return mediaTaskStoreService.getTaskResult(taskId);
    }

    @Override
    public void consumeQueuedTasks() {
        throw new UnsupportedOperationException("当前实现已切换为 RocketMQ 消费模式");
    }

    private MediaGenerateTaskRequest normalizeRequest(MediaGenerateTaskRequest request) {
        List<String> angles = request.angles() == null || request.angles().isEmpty()
                ? mediaTaskProperties.getDefaultAngles()
                : request.angles();
        Integer expectedAssetCount = request.expectedAssetCount() == null ? angles.size() : request.expectedAssetCount();
        String callerService = request.callerService() == null || request.callerService().isBlank()
                ? "unknown-caller" : request.callerService();
        String taskType = request.taskType() == null || request.taskType().isBlank()
                ? "LISTING_IMAGE" : request.taskType();
        return new MediaGenerateTaskRequest(
                request.listingId(),
                request.contentId(),
                callerService,
                taskType,
                request.prompt(),
                angles,
                expectedAssetCount
        );
    }

    private void validateRequest(MediaGenerateTaskRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("媒体生成任务不能为空");
        }
        if (request.listingId() == null) {
            throw new IllegalArgumentException("房源ID不能为空");
        }
        if (request.prompt() == null || request.prompt().isBlank()) {
            throw new IllegalArgumentException("生成提示词不能为空");
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待媒体任务结果时被中断", exception);
        }
    }
}
