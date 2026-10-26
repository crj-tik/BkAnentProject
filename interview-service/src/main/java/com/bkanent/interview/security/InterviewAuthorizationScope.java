package com.bkanent.interview.security;

import com.bkanent.common.a2a.A2aCredentialContext;
import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.authz.AuthzResponse;
import com.bkanent.common.authz.AuthorizationContext;
import com.bkanent.common.mcp.authz.McpAuthorizationContract;
import com.bkanent.common.mcp.authz.McpAuthorizationScope;

/** Trusted request identity from a locally verified Gateway proof or an authenticated A2A lease. */
public final class InterviewAuthorizationScope {
    private static final ThreadLocal<AuthzResponse> CURRENT = new ThreadLocal<>();

    private InterviewAuthorizationScope() { }

    public static final class Scope implements AutoCloseable {
        private final AuthzResponse previous;
        private Scope(AuthzResponse value) {
            previous = CURRENT.get();
            if (value == null) CURRENT.remove(); else CURRENT.set(value);
        }
        @Override public void close() {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    public static Scope open(AuthzResponse response) { return new Scope(response); }

    public static long requireActorId() {
        AuthorizationContext context = context();
        try {
            long actor = Long.parseLong(context.actorId());
            if (actor <= 0) throw new NumberFormatException();
            return actor;
        } catch (NumberFormatException invalid) {
            throw new AuthzException(AuthzErrorCode.AUTHENTICATION_FAILED);
        }
    }

    public static long permissionRevision() { return context().permissionRevision(); }

    public static long credentialEpoch() { return context().credentialEpoch(); }

    public static long expiresAtEpochSecond() {
        A2aCredentialContext.Lease lease = A2aCredentialContext.snapshot();
        if (lease != null) {
            lease.verify();
            if (!lease.versioned() || lease.freshness() == null) {
                throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
            }
            return Math.min(lease.authorizationContext().expiresAtEpochSecond(),
                    lease.freshness().notAfterEpochSecond());
        }
        McpAuthorizationContract.Verified mcp = McpAuthorizationScope.current();
        if (mcp != null) {
            return Math.min(mcp.binding().expiresAtEpochSecond(),
                    Math.min(mcp.context().expiresAtEpochSecond(), mcp.freshness().notAfterEpochSecond()));
        }
        AuthzResponse response = CURRENT.get();
        if (response == null) throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
        return Math.min(response.context().expiresAtEpochSecond(), response.freshness().notAfterEpochSecond());
    }

    public static void requirePermission(String permission) {
        if (permission == null || !context().allowedOperations().contains(permission)) {
            throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
        }
        McpAuthorizationContract.Verified mcp = McpAuthorizationScope.current();
        if (mcp != null && !permission.equals(mcp.operation())) {
            throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
        }
    }

    public static boolean hasPermission(String permission) {
        return permission != null && context().allowedOperations().contains(permission);
    }

    private static AuthorizationContext context() {
        A2aCredentialContext.Lease lease = A2aCredentialContext.snapshot();
        if (lease != null) {
            lease.verify();
            AuthorizationContext context = lease.authorizationContext();
            if (context == null) throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
            return context;
        }
        McpAuthorizationContract.Verified mcp = McpAuthorizationScope.current();
        if (mcp != null) return mcp.context();
        AuthzResponse response = CURRENT.get();
        if (response == null) throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
        return response.context();
    }
}
