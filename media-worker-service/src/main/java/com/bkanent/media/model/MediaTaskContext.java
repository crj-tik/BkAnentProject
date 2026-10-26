package com.bkanent.media.model;

import com.bkanent.common.model.MediaGenerateTaskRequest;
import com.bkanent.common.model.MediaTaskResultDTO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 媒体任务上下文。
 */
public class MediaTaskContext {

    private final String taskId;
    private final MediaGenerateTaskRequest request;
    private final MediaBackgroundPermit permit;
    /**
     * 业务属性：result。
     */
    private volatile MediaTaskResultDTO result;

    public MediaTaskContext(String taskId, MediaGenerateTaskRequest request, MediaBackgroundPermit permit) {
        this.taskId = taskId;
        this.request = request;
        this.permit = permit;
        this.result = new MediaTaskResultDTO(
                taskId,
                request.listingId(),
                request.contentId(),
                request.callerService(),
                request.taskType(),
                MediaTaskStatusEnum.QUEUED.name(),
                List.of(),
                null,
                null,
                "任务已入队，等待 Worker 消费",
                LocalDateTime.now(),
                null
        );
    }

    public String getTaskId() {
        return taskId;
    }

    public MediaGenerateTaskRequest getRequest() {
        return request;
    }

    public MediaBackgroundPermit getPermit() { return permit; }

    public MediaTaskResultDTO getResult() {
        return result;
    }

    public void setResult(MediaTaskResultDTO result) {
        this.result = result;
    }
}
