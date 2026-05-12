package com.bkanent.media.rpc;

import com.bkanent.common.model.MediaGenerateTaskRequest;
import com.bkanent.common.model.MediaTaskResultDTO;
import com.bkanent.common.rpc.MediaWorkerRpcService;
import com.bkanent.media.service.MediaGenerationTaskService;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

/**
 * 媒体内容生成 Worker RPC 服务实现。
 */
@DubboService
public class MediaWorkerRpcServiceImpl implements MediaWorkerRpcService {

    private final MediaGenerationTaskService mediaGenerationTaskService;

    public MediaWorkerRpcServiceImpl(MediaGenerationTaskService mediaGenerationTaskService) {
        this.mediaGenerationTaskService = mediaGenerationTaskService;
    }

    @Override
    public List<String> generateAssets(Long listingId, String prompt) {
        return mediaGenerationTaskService.generateAssetsSync(listingId, prompt);
    }

    @Override
    public String submitGenerateTask(MediaGenerateTaskRequest request) {
        return mediaGenerationTaskService.submitTask(request);
    }

    @Override
    public MediaTaskResultDTO getTaskResult(String taskId) {
        return mediaGenerationTaskService.getTaskResult(taskId);
    }
}
