package com.bkanent.common.mcp.authz;

import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;

/** Request-thread view for domain adapters that need the locally verified MCP actor. */
public final class McpAuthorizationScope {
    public static final String REQUEST_ATTRIBUTE = McpAuthorizationScope.class.getName() + ".verified";
    private static final ThreadLocal<McpAuthorizationContract.Verified> CURRENT = new ThreadLocal<>();

    private McpAuthorizationScope() { }

    public static McpAuthorizationContract.Verified current() { return CURRENT.get(); }

    /** Returns only the actor carried by a proof already verified at the MCP request boundary. */
    public static String requireActor() {
        McpAuthorizationContract.Verified verified = CURRENT.get();
        if (verified == null) throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
        return verified.actorId();
    }

    public static AutoCloseable open(McpAuthorizationContract.Verified verified) {
        McpAuthorizationContract.Verified previous = CURRENT.get();
        CURRENT.remove();
        if (verified != null) CURRENT.set(verified);
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }
}
