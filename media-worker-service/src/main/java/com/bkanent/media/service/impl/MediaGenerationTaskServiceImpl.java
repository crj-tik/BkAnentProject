package com.bkanent.media.service.impl;

import com.bkanent.common.model.MediaGenerateTaskRequest;
import com.bkanent.common.model.MediaTaskResultDTO;
import com.bkanent.common.a2a.VerifiedDomainAuthorization;
import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.media.config.MediaRocketMqProperties;
import com.bkanent.media.config.MediaTaskProperties;
import com.bkanent.media.model.MediaGenerateTaskMessage;
import com.bkanent.media.model.MediaBackgroundPermit;
import com.bkanent.media.model.MediaTaskContext;
import com.bkanent.media.model.MediaTaskStatusEnum;
import com.bkanent.media.service.MediaGenerationTaskService;
import com.bkanent.media.service.MediaBackgroundPermitSigner;
import com.bkanent.media.service.MediaListingAuthorizationClient;
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
    private final MediaBackgroundPermitSigner permitSigner;
    private final MediaListingAuthorizationClient listingAuthorizationClient;

    public MediaGenerationTaskServiceImpl(RocketMQTemplate rocketMQTemplate,
                                          MediaRocketMqProperties mediaRocketMqProperties,
                                          MediaTaskProperties mediaTaskProperties,
                                          MediaTaskStoreService mediaTaskStoreService,
                                          MediaBackgroundPermitSigner permitSigner,
                                          MediaListingAuthorizationClient listingAuthorizationClient) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.mediaRocketMqProperties = mediaRocketMqProperties;
        this.mediaTaskProperties = mediaTaskProperties;
        this.mediaTaskStoreService = mediaTaskStoreService;
        this.permitSigner = permitSigner;
        this.listingAuthorizationClient = listingAuthorizationClient;
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
        VerifiedDomainAuthorization.requireActorScope("media.task.submit.own", "media.task.submit.all");
        VerifiedDomainAuthorization.requirePermission(MediaBackgroundPermitSigner.BACKGROUND_OPERATION);
        var verified = VerifiedDomainAuthorization.requireVerifiedContext();
        listingAuthorizationClient.requireAssetWrite(request.listingId());
        String taskId = UUID.randomUUID().toString().replace("-", "");
        MediaGenerateTaskRequest normalizedRequest = normalizeRequest(request);
        MediaBackgroundPermit permit = permitSigner.create(taskId, normalizedRequest, verified);
        mediaTaskStoreService.createQueuedTask(taskId, normalizedRequest, permit);
        try {
            rocketMQTemplate.convertAndSend(mediaRocketMqProperties.getGenerateTopic(),
                    new MediaGenerateTaskMessage(taskId, permit));
        } catch (RuntimeException failure) {
            mediaTaskStoreService.markFailed(taskId, "媒体任务入队失败");
            throw new AuthzException(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE);
        }
        return taskId;
    }

    @Override
    public MediaTaskResultDTO getTaskResult(String taskId) {
        String ownerActorId = VerifiedDomainAuthorization.requireActorScope(
                "media.task.read.own", "media.task.read.all");
        boolean businessWide = ownerActorId == null;
        return mediaTaskStoreService.getTaskResult(taskId, ownerActorId, businessWide);
    }

    @Override
    public MediaTaskContext claimTaskForWorker(String taskId, MediaBackgroundPermit permit) {
        MediaTaskContext stored = mediaTaskStoreService.findTaskContext(taskId);
        if (!stored.getPermit().equals(permit)) throw new AuthzException(AuthzErrorCode.AUTHENTICATION_FAILED);
        if (permitSigner.isExpired(permit)) return mediaTaskStoreService.claimQueuedTask(taskId, permit);
        try {
            permitSigner.verify(permit, taskId, stored.getRequest());
        } catch (AuthzException failure) {
            if (permitSigner.isExpired(permit)) return mediaTaskStoreService.claimQueuedTask(taskId, permit);
            throw failure;
        }
        return mediaTaskStoreService.claimQueuedTask(taskId, permit);
    }

    @Override
    public void consumeQueuedTasks() {
        throw new UnsupportedOperationException("当前实现已切换为 RocketMQ 消费模式");
    }

    private MediaGenerateTaskRequest normalizeRequest(MediaGenerateTaskRequest request) {
        List<String> angles = request.angles() == null || request.angles().isEmpty()
                ? mediaTaskProperties.getDefaultAngles()
                : request.angles();
        if (angles == null || angles.isEmpty()) {
            throw new IllegalArgumentException("媒体生成角度不能为空");
        }
        Integer expectedAssetCount = request.expectedAssetCount() == null ? angles.size() : request.expectedAssetCount();
        if (expectedAssetCount != angles.size()) {
            throw new IllegalArgumentException("媒体素材数量必须与生成角度一致");
        }
        return new MediaGenerateTaskRequest(
                request.listingId(),
                null,
                "media-worker-service",
                "LISTING_IMAGE",
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
        if (request.listingId() <= 0 || request.contentId() != null
                || request.taskType() != null && !"LISTING_IMAGE".equals(request.taskType())
                || request.prompt().length() > 4000) {
            throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
        }
        if (request.angles() != null && (request.angles().size() > 8
                || request.angles().stream().anyMatch(angle -> angle == null || angle.isBlank() || angle.length() > 100))) {
            throw new IllegalArgumentException("媒体生成角度无效");
        }
        if (request.expectedAssetCount() != null && (request.expectedAssetCount() < 1
                || request.expectedAssetCount() > 8)) {
            throw new IllegalArgumentException("媒体素材数量无效");
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
