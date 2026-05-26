package com.bkanent.agent.service;

import com.bkanent.agent.model.distributed.SessionEventAuditView;
import com.bkanent.agent.stream.SessionEventAuditService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SupervisorEventAuditQueryService {

    private final SessionEventAuditService sessionEventAuditService;

    public SupervisorEventAuditQueryService(SessionEventAuditService sessionEventAuditService) {
        this.sessionEventAuditService = sessionEventAuditService;
    }

    public List<SessionEventAuditView> query(String taskId,
                                             String traceId,
                                             String approvalId,
                                             String artifactId,
                                             String asyncTaskId,
                                             String grayStrategyVersion,
                                             String asyncWorkflowId,
                                             Boolean includeArchived,
                                             Integer limit) {
        return sessionEventAuditService.query(
                taskId,
                traceId,
                approvalId,
                artifactId,
                asyncTaskId,
                grayStrategyVersion,
                asyncWorkflowId,
                includeArchived,
                limit
        );
    }
}
