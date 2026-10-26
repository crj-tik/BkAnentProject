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
    private com.bkanent.agent.config.AgentAuthorizationProperties authorizationProperties;

    public AgentPermissionService(AuthPermissionRpcService authPermissionRpcService,
                                  PermissionAuditService permissionAuditService) {
        this.authPermissionRpcService = authPermissionRpcService;
        this.permissionAuditService = permissionAuditService;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setAuthorizationProperties(com.bkanent.agent.config.AgentAuthorizationProperties properties) {
        this.authorizationProperties = properties;
    }

    public void assertCanReadEventAudit(String actorId) {
        assertPermission(actorId, "agent.audit.read", "owned event audit query");
    }

    public void assertCanReadBusinessEventAudit(String actorId) {
        assertPermission(actorId, "agent.audit.read", "business event audit query");
        assertPermission(actorId, "agent.audit.manage", "business event audit scope");
    }

    public void assertCanReadGovernance(String actorId) {
        assertPermission(actorId, "agent.governance.read", "governance query");
    }

    public void assertCanManageGovernance(String actorId) {
        assertPermission(actorId, "agent.governance.manage", "governance change");
    }

    public void assertCanReadTaskArtifacts(String userId) {
        Long parsedUserId = parseUserId(userId);
        if (parsedUserId == null || !hasPermission(parsedUserId, ARTIFACT_READ_PERMISSION)) {
            permissionAuditService.publishDenied(null, null, "workflow artifact query",
                    java.util.Map.of("userId", userId, "permissionCode", ARTIFACT_READ_PERMISSION), null);
            throw new IllegalStateException("permission denied for workflow artifact query");
        }
    }

    /**
     * 审批待办列表查询权限：复用工作流读权限（待办是工作流状态的一部分；
     * 列表内的 owner 过滤在查询服务内完成，口径与单任务工作流查询一致）。
     */
    public void assertCanReadApprovalTodos(String userId) {
        assertPermission(userId, WORKFLOW_READ_PERMISSION, "approval todo query");
    }

    public void assertCanReadWorkflow(String requesterUserId,
                                      String ownerUserId,
                                      String sessionId,
                                      String taskId,
                                      String traceId) {
        Long requesterId = parseUserId(requesterUserId);
        Long ownerId = parseUserId(ownerUserId);
        if (requesterId == null || ownerId == null || !requesterId.equals(ownerId)
                || !hasPermission(requesterId, WORKFLOW_READ_PERMISSION)) {
            permissionAuditService.publishDenied(sessionId, taskId, "workflow query",
                    java.util.Map.of(
                            "userId", requesterUserId == null ? "" : requesterUserId,
                            "ownerUserId", ownerUserId == null ? "" : ownerUserId,
                            "permissionCode", WORKFLOW_READ_PERMISSION
                    ), traceId);
            throw new IllegalStateException("permission denied for workflow query");
        }
    }

    public void assertPermission(String userId, String permissionCode, String action) {
        Long parsedUserId = parseUserId(userId);
        if (parsedUserId == null || !hasPermission(parsedUserId, permissionCode)) {
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
                || !hasPermission(requesterId, WORKFLOW_READ_PERMISSION)) {
            permissionAuditService.publishDenied(sessionId, taskId, action,
                    java.util.Map.of(
                            "userId", requesterUserId == null ? "" : requesterUserId,
                            "ownerUserId", ownerUserId == null ? "" : ownerUserId,
                            "permissionCode", WORKFLOW_READ_PERMISSION
                    ), traceId);
            throw new IllegalStateException("permission denied for " + action);
        }
    }

    public boolean canReadMcpServer(String userId, String serverName) {
        Long parsedUserId = parseUserId(userId);
        if (parsedUserId == null) return false;
        if (versionedAuthorizationEnabled()) {
            String capability = capabilityId(() -> com.bkanent.common.agent.CapabilityId.mcp(serverName, "placeholder"));
            if (capability == null) return false;
            String prefix = capability.substring(0, capability.lastIndexOf(':') + 1);
            return authorizationProperties.getCapabilityPermissions().entrySet().stream()
                    .filter(mapping -> mapping.getKey().startsWith(prefix))
                    .anyMatch(mapping -> hasPermission(parsedUserId, mapping.getValue()));
        }
        String permission = resolveMcpServerPermission(serverName);
        return permission != null && hasPermission(parsedUserId, permission);
    }

    public boolean canInvokeChildAgent(String userId, RegisteredAgentDescriptor descriptor) {
        Long parsedUserId = parseUserId(userId);
        if (parsedUserId == null || descriptor == null) return false;
        if (versionedAuthorizationEnabled()) {
            return hasMappedCapabilityPermission(parsedUserId, capabilityId(
                    () -> com.bkanent.common.agent.CapabilityId.a2a(descriptor.agentId())));
        }
        return hasPermission(parsedUserId, resolveInvokePermissionCode(descriptor, null));
    }

    public boolean canUseLocalTools(String userId) {
        Long parsedUserId = parseUserId(userId);
        if (versionedAuthorizationEnabled()) return anyMappedCapabilityPermission(parsedUserId, "local:");
        return parsedUserId != null && hasPermission(parsedUserId, "agent.chat.use");
    }
    public boolean canUseMcpTools(String userId) {
        Long parsedUserId = parseUserId(userId);
        if (versionedAuthorizationEnabled()) return anyMappedCapabilityPermission(parsedUserId, "mcp:");
        return parsedUserId != null && hasPermission(parsedUserId, "agent.mcp.chat.use");
    }

    public boolean canUseLocalTool(String userId, String toolName) {
        Long parsedUserId = parseUserId(userId);
        if (parsedUserId == null) return false;
        if (!versionedAuthorizationEnabled()) return hasPermission(parsedUserId, "agent.chat.use");
        return hasMappedCapabilityPermission(parsedUserId,
                capabilityId(() -> com.bkanent.common.agent.CapabilityId.local(toolName)));
    }

    public boolean canInvokeMcpTool(String userId, String serverName, String toolName) {
        Long parsedUserId = parseUserId(userId);
        if (parsedUserId == null) return false;
        if (!versionedAuthorizationEnabled()) {
            return hasPermission(parsedUserId, "agent.mcp.chat.use")
                    && canReadMcpServer(userId, serverName);
        }
        return hasMappedCapabilityPermission(parsedUserId,
                capabilityId(() -> com.bkanent.common.agent.CapabilityId.mcp(serverName, toolName)));
    }

    public boolean versionedAuthorizationEnabled() {
        return authorizationProperties != null && authorizationProperties.isEnabled();
    }

    public void assertCanInvokeChildAgent(RegisteredAgentDescriptor descriptor, AgentTaskInvokeRequest request) {
        Long userId = resolveUserId(request);
        String capabilityId = descriptor == null ? null
                : capabilityId(() -> com.bkanent.common.agent.CapabilityId.a2a(descriptor.agentId()));
        String permissionCode = versionedAuthorizationEnabled()
                ? capabilityId == null ? null : authorizationProperties.permissionForCapability(capabilityId)
                : descriptor == null ? null : resolveInvokePermissionCode(descriptor, request);
        boolean permitted = userId != null && descriptor != null && (versionedAuthorizationEnabled()
                ? canInvokeChildAgent(Long.toString(userId), descriptor)
                : hasPermission(userId, permissionCode));
        if (!permitted) {
            permissionAuditService.publishDenied(
                    request == null ? null : request.sessionId(),
                    request == null ? null : request.taskId(),
                    "child agent invocation",
                    java.util.Map.of(
                            "userId", userId == null ? "" : String.valueOf(userId),
                            "permissionCode", permissionCode == null ? "" : permissionCode,
                            "targetAgentId", descriptor == null ? "" : descriptor.agentId()
                    ),
                    request == null ? null : request.traceId()
            );
            throw new IllegalStateException("permission denied for child agent invocation: "
                    + (descriptor == null ? "unknown" : descriptor.agentId()));
        }
    }

    private Long resolveUserId(AgentTaskInvokeRequest request) {
        if (versionedAuthorizationEnabled()) {
            var entry = com.bkanent.agent.authorization.AgentAuthorizationScope.current();
            if (entry == null) throw new com.bkanent.common.authz.AuthzException(
                    com.bkanent.common.authz.AuthzErrorCode.AUTHENTICATION_REQUIRED);
            return parseUserId(entry.requireActor(null));
        }
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

    private boolean hasPermission(Long userId, String permissionCode) {
        if (permissionCode == null) return false;
        var entry = com.bkanent.agent.authorization.AgentAuthorizationScope.current();
        if (versionedAuthorizationEnabled()) {
            if (entry == null) throw new com.bkanent.common.authz.AuthzException(
                    com.bkanent.common.authz.AuthzErrorCode.AUTHENTICATION_REQUIRED);
            entry.requireActor(Long.toString(userId));
            try {
                entry.session().requirePermission(permissionCode);
                return true;
            } catch (com.bkanent.common.authz.AuthzException denied) {
                if (denied.code() == com.bkanent.common.authz.AuthzErrorCode.PERMISSION_DENIED) return false;
                throw denied;
            }
        }
        return authPermissionRpcService.hasPermission(userId, permissionCode);
    }

    private boolean hasMappedCapabilityPermission(Long userId, String capabilityId) {
        if (userId == null || capabilityId == null || !versionedAuthorizationEnabled()) return false;
        String permissionCode = authorizationProperties.permissionForCapability(capabilityId);
        return permissionCode != null && hasPermission(userId, permissionCode);
    }

    private boolean anyMappedCapabilityPermission(Long userId, String prefix) {
        return userId != null && versionedAuthorizationEnabled()
                && authorizationProperties.getCapabilityPermissions().entrySet().stream()
                .filter(mapping -> mapping.getKey().startsWith(prefix))
                .anyMatch(mapping -> hasPermission(userId, mapping.getValue()));
    }

    private String capabilityId(java.util.function.Supplier<com.bkanent.common.agent.CapabilityId> supplier) {
        try { return supplier.get().value(); }
        catch (IllegalArgumentException invalidCapability) { return null; }
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
        return null;
    }
}
