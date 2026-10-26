package com.bkanent.media.service;

import com.bkanent.common.model.MediaGenerateTaskRequest;
import com.bkanent.common.model.MediaTaskResultDTO;
import com.bkanent.media.model.MediaBackgroundPermit;
import com.bkanent.media.model.MediaTaskContext;

import java.util.List;

/**
 * 媒体生成任务服务接口。
 */
public interface MediaGenerationTaskService {

    /**
     * 业务方法：generateAssetsSync。
     */
    List<String> generateAssetsSync(Long listingId, String prompt);

    /**
     * 业务方法：submitTask。
     */
    String submitTask(MediaGenerateTaskRequest request);

    /**
     * 业务方法：getTaskResult。
     */
    MediaTaskResultDTO getTaskResult(String taskId);

    /** Claims one queued task only after its exact background permit and Auth signatures verify. */
    MediaTaskContext claimTaskForWorker(String taskId, MediaBackgroundPermit permit);

    /**
     * 业务方法：consumeQueuedTasks。
     */
    void consumeQueuedTasks();
}
