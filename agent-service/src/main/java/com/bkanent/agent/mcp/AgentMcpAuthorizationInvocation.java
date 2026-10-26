package com.bkanent.agent.mcp;

import com.bkanent.agent.authorization.AgentAuthorizationScope;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.authz.AuthzErrorCode;

/** Trusted invocation selected by the Supervisor callback; never populated from tool arguments. */
public record AgentMcpAuthorizationInvocation(AgentAuthorizationScope.Entry entry, String serverName,
                                               String toolName, boolean delegated, boolean publicData,
                                               String audience, String operation) {
    public AgentMcpAuthorizationInvocation {
        if (entry == null || serverName == null || serverName.isBlank() || toolName == null || toolName.isBlank()
                || delegated == publicData || (delegated && (audience == null || operation == null))) {
            throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
        }
    }
}
