package com.bkanent.agent.graph.official;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.bkanent.agent.model.distributed.SupervisorTaskRequest;

import java.util.Map;

public interface OfficialSupervisorGraphMigrationFacade {

    Map<String, Object> initializeState(SupervisorTaskRequest request,
                                        String sessionId,
                                        String taskId,
                                        String traceId);

    RunnableConfig runnableConfig(String sessionId, String taskId);
}
