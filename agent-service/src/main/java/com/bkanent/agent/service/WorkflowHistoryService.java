package com.bkanent.agent.service;

import com.bkanent.agent.memory.MemoryStoreClient;
import com.bkanent.common.agent.WorkflowHistoryView;
import org.springframework.stereotype.Service;

/**
 * WorkflowHistoryService 组装工作流的按时间排列的步骤历史。
 */
@Service
public class WorkflowHistoryService {

    private final MemoryStoreClient memoryStoreClient;

    public WorkflowHistoryService(MemoryStoreClient memoryStoreClient) {
        this.memoryStoreClient = memoryStoreClient;
    }

    public WorkflowHistoryView assembleHistory(String taskId) {
        return memoryStoreClient.getWorkflowHistory(taskId);
    }
}
