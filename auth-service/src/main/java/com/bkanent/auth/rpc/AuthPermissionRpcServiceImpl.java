package com.bkanent.auth.rpc;

import com.bkanent.auth.entity.UserAccountEntity;
import com.bkanent.auth.service.AuthTokenService;
import com.bkanent.auth.service.UserManagementService;
import com.bkanent.auth.service.UserAccountService;
import com.bkanent.common.rpc.AuthPermissionRpcService;
import com.bkanent.common.rpc.AuthenticatedPrincipal;
import com.bkanent.common.authz.AuthzOperationPolicy;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.util.StringUtils;

/**
 * AuthPermissionRpcServiceImpl RPC 服务实现类。
 */
@DubboService
public class AuthPermissionRpcServiceImpl implements AuthPermissionRpcService {

    private final AuthTokenService authTokenService;
    private final UserAccountService userAccountService;
    private final UserManagementService userManagementService;

    public AuthPermissionRpcServiceImpl(AuthTokenService authTokenService,
                                        UserAccountService userAccountService,
                                        UserManagementService userManagementService) {
        this.authTokenService = authTokenService;
        this.userAccountService = userAccountService;
        this.userManagementService = userManagementService;
    }

    @Override
    public boolean validateToken(String token) {
        return resolvePrincipal(token) != null;
    }

    @Override
    public AuthenticatedPrincipal resolvePrincipal(String token) {
        AuthTokenService.TokenPrincipal tokenPrincipal = authTokenService.parse(token);
        if (tokenPrincipal == null || !"access".equals(tokenPrincipal.tokenType())) {
            return null;
        }
        UserAccountEntity account = userAccountService.getById(tokenPrincipal.userId());
        if (!isActive(account)) {
            return null;
        }
        return new AuthenticatedPrincipal(tokenPrincipal.userId());
    }

    @Override
    public boolean hasPermission(Long userId, String permissionCode) {
        if (userId == null || !StringUtils.hasText(permissionCode)) {
            return false;
        }
        if (userManagementService.isVersionedAuthorization()) {
            try {
                var authorization = userManagementService.authorization(userId);
                return "ACTIVE".equals(authorization.status())
                        && (AuthzOperationPolicy.isBaseline(permissionCode)
                        || authorization.hasPermission(permissionCode, java.time.Instant.now()));
            } catch (com.bkanent.auth.service.UserManagementException missing) { return false; }
        }
        UserAccountEntity account = userAccountService.getById(userId);
        if (!isActive(account)) return false;
        if (AuthzOperationPolicy.isBaseline(permissionCode)) return true;
        if (java.util.Set.of("agent.rag.manage", "agent.vector-store.manage", "agent.mcp.vector-store.manage",
                "rag.space.manage", "memory.constraint.manage", "auth.users.manage").contains(permissionCode)
                || permissionCode.endsWith(".admin") || permissionCode.endsWith(".manage")
                || permissionCode.endsWith(".init") || permissionCode.endsWith(".provision")
                || permissionCode.endsWith(".replay") || permissionCode.endsWith(".index")) {
            return "ADMIN".equals(account.getRoleCode());
        }
        return permissionCode.equals("agent.chat.use") || permissionCode.equals("agent.mcp.chat.use")
                || permissionCode.equals("agent.workflow.read") || permissionCode.equals("agent.workflow.artifact.read")
                || permissionCode.startsWith("agent.workflow.invoke.") || permissionCode.startsWith("agent.mcp.server.")
                || java.util.Set.of("agent.rag.read", "agent.rag.search", "agent.vector-store.read",
                    "agent.mcp.vector-store.read", "agent.rag.memory.search", "agent.rag.listing.query",
                    "agent.mcp.tools.read", "agent.mcp.catalog.read", "agent.mcp.debug.read", "listing.read", "memory.constraint.read").contains(permissionCode);
    }

    private boolean isActive(UserAccountEntity account) {
        if (userManagementService.isVersionedAuthorization()) return userManagementService.isAuthorizedAccount(account);
        return account != null && Integer.valueOf(0).equals(account.getDeleted())
                && "ACTIVE".equalsIgnoreCase(account.getAccountStatus())
                && UserManagementService.isSupportedRole(account.getRoleCode())
                && userManagementService.isActiveRole(account.getRoleCode());
    }
}


