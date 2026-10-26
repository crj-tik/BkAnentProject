package com.bkanent.common.mcp.authz;

import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import java.time.Instant;

/** Request-thread view for domain adapters that need the locally verified MCP actor. */
public final class McpAuthorizationScope {
    public static final String REQUEST_ATTRIBUTE = McpAuthorizationScope.class.getName() + ".verified";
    private static final ThreadLocal<McpAuthorizationContract.Verified> CURRENT = new ThreadLocal<>();

    private McpAuthorizationScope() { }

    public static McpAuthorizationContract.Verified current() { return CURRENT.get(); }

    /** Returns a verified request after rechecking its local hard deadlines. */
    public static McpAuthorizationContract.Verified requireCurrent() {
        McpAuthorizationContract.Verified verified = CURRENT.get();
        if (verified == null) throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
        requireLive(verified);
        return verified;
    }

    /** Returns only the actor carried by a proof already verified at the MCP request boundary. */
    public static String requireActor() {
        McpAuthorizationContract.Verified verified = CURRENT.get();
        if (verified == null) throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
        requireLive(verified);
        return verified.actorId();
    }

    /** Checks the exact operation verified for this MCP request without contacting Auth. */
    public static void requireOperation(String operation) {
        McpAuthorizationContract.Verified verified = CURRENT.get();
        if (verified == null) throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
        requireLive(verified);
        if (operation == null || !operation.equals(verified.operation())
                || !verified.context().allowedOperations().contains(operation)) {
            throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
        }
    }

    /** Checks a secondary permission needed by the verified tool's domain operation. */
    public static void requireContextPermission(String permission) {
        McpAuthorizationContract.Verified verified = CURRENT.get();
        if (verified == null) throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
        requireLive(verified);
        if (permission == null || !verified.context().allowedOperations().contains(permission)) {
            throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
        }
    }

    private static void requireLive(McpAuthorizationContract.Verified verified) {
        long now = Instant.now().getEpochSecond();
        if (now >= verified.context().expiresAtEpochSecond()
                || now >= verified.context().runHardDeadlineEpochSecond()
                || now >= verified.freshness().notAfterEpochSecond()
                || now >= verified.binding().expiresAtEpochSecond()) {
            throw new AuthzException(AuthzErrorCode.EXPIRED);
        }
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
