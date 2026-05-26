package com.bkanent.agent.service;

import com.bkanent.agent.model.distributed.SupervisorTaskResponse;
import com.bkanent.common.agent.ApprovalCallbackRequest;

/**
 * ApprovalCommandService 审批回调服务。
 */
public interface ApprovalCommandService {

    SupervisorTaskResponse handleCallback(ApprovalCallbackRequest request);
}
