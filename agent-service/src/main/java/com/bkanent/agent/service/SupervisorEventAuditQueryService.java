package com.bkanent.agent.service;

import com.bkanent.agent.model.distributed.SessionEventAuditView;
import com.bkanent.agent.orchestration.OrchestrationStore;
import com.bkanent.agent.security.AgentPrincipalContext;
import com.bkanent.agent.stream.SessionEventAuditService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class SupervisorEventAuditQueryService {

    private final SessionEventAuditService audit;
    private final AgentPrincipalContext principal;
    private final AgentPermissionService permissions;
    private final OrchestrationStore store;
    private final SupervisorSessionOwnershipService ownership;

    public SupervisorEventAuditQueryService(SessionEventAuditService audit, AgentPrincipalContext principal,
                                            AgentPermissionService permissions, OrchestrationStore store,
                                            SupervisorSessionOwnershipService ownership) {
        this.audit = audit;
        this.principal = principal;
        this.permissions = permissions;
        this.store = store;
        this.ownership = ownership;
    }

    public List<SessionEventAuditView> query(String taskId, String traceId, String approvalId, String artifactId,
                                             String asyncTaskId, String grayStrategyVersion, String asyncWorkflowId,
                                             Boolean includeArchived, Integer limit) {
        String actorId = principal.userId();
        permissions.assertCanReadEventAudit(actorId);
        var scopes = store.ownedRunScopes(actorId).stream()
                .filter(scope -> taskId == null || taskId.isBlank() || taskId.equals(scope.runId())).toList();
        // Run JSON only locates candidates. Memory's persistent session owner is authoritative.
        for (String sessionId : scopes.stream().map(OrchestrationStore.RunScope::sessionId).distinct().toList()) {
            ownership.assertOwned(sessionId, actorId);
        }
        var result = audit.queryOwned(scopes, taskId, traceId, approvalId, artifactId, asyncTaskId,
                grayStrategyVersion, asyncWorkflowId, includeArchived, limit);
        for (String sessionId : scopes.stream().map(OrchestrationStore.RunScope::sessionId).distinct().toList()) {
            ownership.assertOwned(sessionId, actorId);
        }
        return result;
    }

    public List<SessionEventAuditView> queryBusinessDiagnostics(String taskId, String traceId, String approvalId,
                                                               String artifactId, String asyncTaskId,
                                                               String grayStrategyVersion, String asyncWorkflowId,
                                                               Boolean includeArchived, Integer limit) {
        String actorId = principal.userId();
        permissions.assertCanReadBusinessEventAudit(actorId);
        audit.recordRequired(new com.bkanent.common.agent.SessionStreamEvent("", null, "supervisor", "security.audit_access",
                "Business event audit query", Map.of("actorId", actorId, "scope", "business-events",
                "authorizationVersion", "legacy-rpc-unversioned"), null, System.currentTimeMillis()));
        return audit.queryBusinessDiagnostics(taskId, traceId, approvalId, artifactId, asyncTaskId,
                grayStrategyVersion, asyncWorkflowId, includeArchived, limit);
    }
}
