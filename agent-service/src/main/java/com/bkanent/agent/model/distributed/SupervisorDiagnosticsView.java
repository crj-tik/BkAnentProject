package com.bkanent.agent.model.distributed;

import java.util.List;

public record SupervisorDiagnosticsView(
        String lookupType,
        String lookupValue,
        SupervisorWorkflowView workflow,
        TaskArtifactView artifact,
        List<TaskArtifactView> taskArtifacts,
        List<Object> handoffs,
        SupervisorAsyncTaskView asyncTask,
        SupervisorAsyncWorkflowView asyncWorkflow
) {
}
