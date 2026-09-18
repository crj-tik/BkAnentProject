package com.bkanent.agent.graph.official;

import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.agent.model.distributed.SupervisorTaskResponse;
import com.bkanent.common.agent.ApprovalCallbackRequest;

public interface OfficialSupervisorGraphFacade {

    SupervisorTaskResponse execute(SupervisorTaskRequest request);

    SupervisorTaskResponse resume(ApprovalCallbackRequest request);
}
