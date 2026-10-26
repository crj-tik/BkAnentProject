package com.bkanent.media.service.impl;

import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.model.MediaGenerateTaskRequest;
import com.bkanent.common.model.MediaTaskResultDTO;
import com.bkanent.media.model.MediaBackgroundPermit;
import com.bkanent.media.model.MediaTaskContext;
import com.bkanent.media.model.MediaTaskStatusEnum;
import com.bkanent.media.service.MediaTaskStoreService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In-process task ledger; task ids locate records but never act as caller identity. */
@Service
public class MediaTaskStoreServiceImpl implements MediaTaskStoreService {
    private final Map<String, MediaTaskContext> taskStore = new ConcurrentHashMap<>();

    @Override
    public MediaTaskResultDTO createQueuedTask(String taskId, MediaGenerateTaskRequest request,
                                               MediaBackgroundPermit permit) {
        if (taskId == null || request == null || permit == null || !taskId.equals(permit.taskId())) {
            throw new AuthzException(AuthzErrorCode.CONTEXT_CONFLICT);
        }
        MediaTaskContext context = new MediaTaskContext(taskId, request, permit);
        if (taskStore.putIfAbsent(taskId, context) != null) {
            throw new AuthzException(AuthzErrorCode.REPLAY_DETECTED);
        }
        return context.getResult();
    }

    @Override
    public MediaTaskResultDTO markSuccess(String taskId, List<String> assetUrls, String coverImageUrl,
                                          String videoUrl, String message) {
        MediaTaskContext context = requireTask(taskId);
        synchronized (context) {
            requireStatus(context, MediaTaskStatusEnum.RUNNING);
            return setResult(context, MediaTaskStatusEnum.SUCCESS, assetUrls, coverImageUrl, videoUrl,
                    message, LocalDateTime.now());
        }
    }

    @Override
    public MediaTaskResultDTO markFailed(String taskId, String message) {
        MediaTaskContext context = requireTask(taskId);
        synchronized (context) {
            MediaTaskStatusEnum status = MediaTaskStatusEnum.valueOf(context.getResult().status());
            if (status != MediaTaskStatusEnum.RUNNING && status != MediaTaskStatusEnum.QUEUED) {
                throw new AuthzException(AuthzErrorCode.CONTEXT_CONFLICT);
            }
            return setResult(context, MediaTaskStatusEnum.FAILED, List.of(), null, null,
                    message, LocalDateTime.now());
        }
    }

    @Override
    public MediaTaskResultDTO getTaskResult(String taskId, String ownerActorId, boolean businessWide) {
        MediaTaskContext context = taskStore.get(taskId);
        if (context == null || !businessWide && (ownerActorId == null
                || !ownerActorId.equals(context.getPermit().ownerActorId()))) {
            throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
        }
        return context.getResult();
    }

    @Override
    public MediaTaskContext findTaskContext(String taskId) {
        return requireTask(taskId);
    }

    @Override
    public MediaTaskContext claimQueuedTask(String taskId, MediaBackgroundPermit permit) {
        MediaTaskContext context = requireTask(taskId);
        synchronized (context) {
            if (permit == null || !permit.equals(context.getPermit())) {
                throw new AuthzException(AuthzErrorCode.AUTHENTICATION_FAILED);
            }
            if (!MediaTaskStatusEnum.QUEUED.name().equals(context.getResult().status())) return null;
            if (Instant.now().getEpochSecond() >= permit.expiresAtEpochSecond()) {
                setResult(context, MediaTaskStatusEnum.FAILED, List.of(), null, null,
                        "媒体任务授权已过期，任务未执行", LocalDateTime.now());
                return null;
            }
            setResult(context, MediaTaskStatusEnum.RUNNING, List.of(), null, null,
                    "Worker 正在处理消息", null);
            return context;
        }
    }

    private MediaTaskContext requireTask(String taskId) {
        MediaTaskContext context = taskStore.get(taskId);
        if (context == null) throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
        return context;
    }

    private void requireStatus(MediaTaskContext context, MediaTaskStatusEnum expected) {
        if (!expected.name().equals(context.getResult().status())) {
            throw new AuthzException(AuthzErrorCode.CONTEXT_CONFLICT);
        }
    }

    private MediaTaskResultDTO setResult(MediaTaskContext context, MediaTaskStatusEnum status,
                                         List<String> assetUrls, String coverImageUrl, String videoUrl,
                                         String message, LocalDateTime finishedAt) {
        MediaTaskResultDTO current = context.getResult();
        MediaTaskResultDTO updated = new MediaTaskResultDTO(current.taskId(), current.listingId(),
                current.contentId(), current.callerService(), current.taskType(), status.name(),
                assetUrls == null ? List.of() : List.copyOf(assetUrls), coverImageUrl, videoUrl, message,
                current.createdAt(), finishedAt);
        context.setResult(updated);
        return updated;
    }
}
