package com.bkanent.agent.service;

import com.bkanent.agent.config.DistributedAgentProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SupervisorAsyncRuntimeDispatcher {

    private final DistributedAgentProperties distributedAgentProperties;
    private final SupervisorAsyncTaskService supervisorAsyncTaskService;
    private final SupervisorAsyncWorkflowService supervisorAsyncWorkflowService;

    public SupervisorAsyncRuntimeDispatcher(DistributedAgentProperties distributedAgentProperties,
                                            SupervisorAsyncTaskService supervisorAsyncTaskService,
                                            SupervisorAsyncWorkflowService supervisorAsyncWorkflowService) {
        this.distributedAgentProperties = distributedAgentProperties;
        this.supervisorAsyncTaskService = supervisorAsyncTaskService;
        this.supervisorAsyncWorkflowService = supervisorAsyncWorkflowService;
    }

    @Scheduled(fixedDelayString = "${agent.distributed.async-runtime.dispatch-interval-ms:1000}")
    public void dispatch() {
        DistributedAgentProperties.AsyncRuntimeProperties asyncRuntime = distributedAgentProperties.getAsyncRuntime();
        if (!asyncRuntime.isEnabled()) {
            return;
        }
        int batchSize = Math.max(1, asyncRuntime.getDispatchBatchSize());
        supervisorAsyncTaskService.dispatchPendingLocalTasks(batchSize);
        supervisorAsyncWorkflowService.dispatchPendingWorkflows(batchSize);
    }
}
