package com.bkanent.agent.service;

import com.bkanent.agent.mcp.AgentMcpNames;
import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.rpc.AuthPermissionRpcService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AgentPermissionService {

    private static final String ARTIFACT_READ_PERMISSION = "agent.workflow.artifact.read";
    private static final String WORKFLOW_READ_PERMISSION = "agent.workflow.read";

    private final AuthPermissionRpcService authPermissionRpcService;
    private final PermissionAuditService permissionAuditService;

    public AgentPermissionService(AuthPermissionRpcService authPermissionRpcService,
                                  PermissionAuditService permissionAuditService) {
        this.authPermissionRpcService = authPermissionRpcService;
        this.permissionAuditService = permissionAuditService;
    }

    public void assertCanReadTaskArtifacts(String userId) {
        Long parsedUserId = parseUserId(userId);
        if (parsedUserId == null || !authPermissionRpcService.hasPermission(parsedUserId, ARTIFACT_READ_PERMISSION)) {
            permissionAuditService.publishDenied(null, null, "workflow artifact query",
                    java.util.Map.of("userId", userId, "permissionCode", ARTIFACT_READ_PERMISSION), null);
            throw new IllegalStateException("permission denied for workflow artifact query");
        }
    }

    public void assertCanReadWorkflow(String requesterUserId,
                                      String ownerUserId,
                                      String sessionId,
                                      String taskId,
                                      String traceId) {
        Long requesterId = parseUserId(requesterUserId);
        Long ownerId = parseUserId(ownerUserId);
        if (requesterId == null || ownerId == null || !requesterId.equals(ownerId)
                || !authPermissionRpcService.hasPermission(requesterId, WORKFLOW_READ_PERMISSION)) {
            permissionAuditService.publishDenied(sessionId, taskId, "workflow query",
                    java.util.Map.of(
                            "userId", requesterUserId,
                            "ownerUserId", ownerUserId,
                            "permissionCode", WORKFLOW_READ_PERMISSION
                    ), traceId);
            throw new IllegalStateException("permission denied for workflow query");
        }
    }

    public void assertPermission(String userId, String permissionCode, String action) {
        Long parsedUserId = parseUserId(userId);
        if (parsedUserId == null || !authPermissionRpcService.hasPermission(parsedUserId, permissionCode)) {
            permissionAuditService.publishDenied(null, null, action,
                    java.util.Map.of("userId", userId, "permissionCode", permissionCode), null);
            throw new IllegalStateException("permission denied for " + action);
        }
    }

    public void assertCanManageAsyncResource(String requesterUserId,
                                             String ownerUserId,
                                             String sessionId,
                                             String taskId,
                                             String traceId,
                                             String action) {
        Long requesterId = parseUserId(requesterUserId);
        Long ownerId = parseUserId(ownerUserId);
        if (requesterId == null || ownerId == null || !requesterId.equals(ownerId)
                || !authPermissionRpcService.hasPermission(requesterId, WORKFLOW_READ_PERMISSION)) {
            permissionAuditService.publishDenied(sessionId, taskId, action,
                    java.util.Map.of(
                            "userId", requesterUserId,
                            "ownerUserId", ownerUserId,
                            "permissionCode", WORKFLOW_READ_PERMISSION
                    ), traceId);
            throw new IllegalStateException("permission denied for " + action);
        }
    }

    public boolean canReadMcpServer(String userId, String serverName) {
        Long parsedUserId = parseUserId(userId);
        return parsedUserId != null && authPermissionRpcService.hasPermission(parsedUserId, resolveMcpServerPermission(serverName));
    }

    public void assertCanInvokeChildAgent(RegisteredAgentDescriptor descriptor, AgentTaskInvokeRequest request) {
        Long userId = resolveUserId(request);
        String permissionCode = resolveInvokePermissionCode(descriptor, request);
        if (userId == null || !authPermissionRpcService.hasPermission(userId, permissionCode)) {
            permissionAuditService.publishDenied(
                    request == null ? null : request.sessionId(),
                    request == null ? null : request.taskId(),
                    "child agent invocation",
                    java.util.Map.of(
                            "userId", userId == null ? "" : String.valueOf(userId),
                            "permissionCode", permissionCode,
                            "targetAgentId", descriptor.agentId()
                    ),
                    request == null ? null : request.traceId()
            );
            throw new IllegalStateException("permission denied for child agent invocation: " + descriptor.agentId());
        }
    }

    private Long resolveUserId(AgentTaskInvokeRequest request) {
        if (request == null || request.structuredContext() == null) {
            return null;
        }
        Object raw = request.structuredContext().get("userId");
        if (raw == null) {
            return null;
        }
        return parseUserId(String.valueOf(raw));
    }

    private Long parseUserId(String userId) {
        if (!StringUtils.hasText(userId) || "null".equalsIgnoreCase(userId)) {
            return null;
        }
        try {
            return Long.parseLong(userId);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String resolveInvokePermissionCode(RegisteredAgentDescriptor descriptor, AgentTaskInvokeRequest request) {
        String domain = request == null ? null : request.domain();
        if (!StringUtils.hasText(domain) && descriptor.agentCard() != null
                && descriptor.agentCard().supportedDomains() != null
                && !descriptor.agentCard().supportedDomains().isEmpty()) {
            domain = descriptor.agentCard().supportedDomains().get(0);
        }
        if (!StringUtils.hasText(domain)) {
            domain = descriptor.agentId();
        }
        return "agent.workflow.invoke." + domain;
    }

    private String resolveMcpServerPermission(String serverName) {
        if (AgentMcpNames.BUSINESS_SERVER.equalsIgnoreCase(serverName)) {
            return "agent.mcp.server.business.read";
        }
        if (AgentMcpNames.COMPARE_SERVER.equalsIgnoreCase(serverName)) {
            return "agent.mcp.server.compare.read";
        }
        if (AgentMcpNames.MARKETING_SERVER.equalsIgnoreCase(serverName)) {
            return "agent.mcp.server.marketing.read";
        }
        return "agent.mcp.server.generic.read";
    }
}
