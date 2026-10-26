package com.bkanent.common.a2a;

import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.authz.AuthzRequestScope;
import com.bkanent.common.mcp.authz.McpAuthorizationScope;

/** Selects the already verified request context for one local domain permission check. */
public final class VerifiedDomainAuthorization {
    private VerifiedDomainAuthorization() { }

    public static void requireOperation(String operation) {
        if (AuthzRequestScope.present()) {
            AuthzRequestScope.requireOperation(operation);
            return;
        }
        if (McpAuthorizationScope.current() != null) {
            McpAuthorizationScope.requireOperation(operation);
            return;
        }
        if (A2aCredentialContext.snapshot() != null) {
            A2aCredentialContext.requireOperation(operation);
            return;
        }
        throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
    }

    /** Checks another exact permission required by the same domain operation. */
    public static void requirePermission(String permission) {
        if (AuthzRequestScope.present()) {
            AuthzRequestScope.requireOperation(permission);
            return;
        }
        if (McpAuthorizationScope.current() != null) {
            McpAuthorizationScope.requireContextPermission(permission);
            return;
        }
        if (A2aCredentialContext.snapshot() != null) {
            A2aCredentialContext.requireOperation(permission);
            return;
        }
        throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
    }
}
