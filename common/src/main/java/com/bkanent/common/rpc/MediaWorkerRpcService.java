package com.bkanent.common.rpc;

import com.bkanent.common.model.MediaGenerateTaskRequest;
import com.bkanent.common.model.MediaTaskResultDTO;

import java.util.List;

/**
 * 媒体内容生成 Worker RPC 接口。
 */
public interface MediaWorkerRpcService {

    /**
     * 业务方法：generateAssets。
     */
    List<String> generateAssets(Long listingId, String prompt);

    /**
     * 业务方法：submitGenerateTask。
     */
    String submitGenerateTask(MediaGenerateTaskRequest request);

    /**
     * 业务方法：getTaskResult。
     */
    MediaTaskResultDTO getTaskResult(String taskId);
}
