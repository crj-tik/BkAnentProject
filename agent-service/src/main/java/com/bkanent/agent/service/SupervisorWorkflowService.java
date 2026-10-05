package com.bkanent.agent.service;

import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.agent.model.distributed.SupervisorTaskResponse;
import com.bkanent.agent.orchestration.SupervisorToolLoopRunner;
import com.bkanent.common.agent.ApprovalCallbackRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Workflow API facade. Approval callbacks are graph resume commands; this
 * service deliberately contains no route, approval or Agent branching logic.
 */
@Service
public class SupervisorWorkflowService implements ApprovalCommandService {

    private final SupervisorToolLoopRunner toolLoopRunner;

    public SupervisorWorkflowService(SupervisorToolLoopRunner toolLoopRunner) {
        this.toolLoopRunner = toolLoopRunner;
    }

    public SupervisorTaskResponse startWorkflow(SupervisorTaskRequest request) {
        if (request == null || !StringUtils.hasText(request.userMessage())) {
            throw new IllegalArgumentException("userMessage must not be blank");
        }
        return toolLoopRunner.execute(request);
    }

    @Override
    public SupervisorTaskResponse handleCallback(ApprovalCallbackRequest request) {
        if (request == null || !StringUtils.hasText(request.taskId())
                || !StringUtils.hasText(request.approvalId())) {
            throw new IllegalArgumentException("taskId and approvalId are required");
        }
        return toolLoopRunner.resume(request);
    }
}
