package com.bkanent.agent.studio;

import com.bkanent.agent.security.AgentPrincipalContext;
import com.bkanent.common.rpc.AuthPermissionRpcService;
import com.bkanent.common.rpc.AuthenticatedPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;
import org.springframework.http.HttpStatus;

/** Revalidates the current principal before returning private Studio data. */
@Component
@RequestScope
public class StudioIdentity {
    private final AgentPrincipalContext principal;
    private final AuthPermissionRpcService auth;
    private final HttpServletRequest request;

    public StudioIdentity(AgentPrincipalContext principal, AuthPermissionRpcService auth, HttpServletRequest request) {
        this.principal = principal;
        this.auth = auth;
        this.request = request;
    }

    public AuthenticatedPrincipal current() {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7))
            throw new StudioException(HttpStatus.UNAUTHORIZED, "AUTH_401", "请重新登录");
        AuthenticatedPrincipal current = auth.resolvePrincipal(authorization.substring(7).trim());
        if (current == null || current.userId() == null || !principal.userId().equals(String.valueOf(current.userId())))
            throw new StudioException(HttpStatus.UNAUTHORIZED, "AUTH_401", "当前登录状态已失效");
        if (!auth.hasPermission(current.userId(), "agent.workflow.read"))
            throw new StudioException(HttpStatus.FORBIDDEN, "ACTION_NOT_ALLOWED", "当前角色尚未开放此能力");
        return current;
    }

    public String owner() { return String.valueOf(current().userId()); }
    public boolean administrator() { return auth.hasPermission(current().userId(), "auth.users.manage"); }
}
