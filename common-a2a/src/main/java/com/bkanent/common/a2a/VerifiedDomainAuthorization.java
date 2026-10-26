package com.bkanent.common.a2a;

import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.authz.AuthzRequestScope;
import com.bkanent.common.mcp.authz.McpAuthorizationScope;
import com.bkanent.common.authz.AuthorizationContext;

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

    /**
     * Selects a broker-scoped or explicitly business-wide permission without contacting Auth.
     * A null result means the caller has the explicit wide operation; otherwise it is the trusted
     * broker identity from the already verified request context.
     */
    public static Long requireBrokerScope(String ownOperation, String businessWideOperation) {
        if (AuthzRequestScope.present()) {
            if (AuthzRequestScope.businessAdmin()) {
                AuthzRequestScope.requireOperation(businessWideOperation);
                return null;
            }
            AuthzRequestScope.requireOperation(ownOperation);
            return AuthzRequestScope.requireBrokerIdentity();
        }
        if (McpAuthorizationScope.current() != null) {
            AuthorizationContext context = McpAuthorizationScope.requireCurrent().context();
            if (context.allowedOperations().contains(businessWideOperation)) {
                McpAuthorizationScope.requireContextPermission(businessWideOperation);
                return null;
            }
            McpAuthorizationScope.requireOperation(ownOperation);
            return requireBrokerIdentity(context);
        }
        if (A2aCredentialContext.snapshot() != null) {
            A2aCredentialContext.Lease lease = A2aCredentialContext.required();
            AuthorizationContext context = lease.authorizationContext();
            if (context == null) throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
            if (context.allowedOperations().contains(businessWideOperation)) {
                lease.requireOperation(businessWideOperation);
                return null;
            }
            lease.requireOperation(ownOperation);
            return requireBrokerIdentity(context);
        }
        throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
    }

    /** Checks both the exact operation and the signed owner identity for one broker resource. */
    public static void requireBrokerOwnership(Long resourceBrokerId, String ownOperation,
                                              String businessWideOperation) {
        Long brokerId = requireBrokerScope(ownOperation, businessWideOperation);
        if (brokerId != null && (resourceBrokerId == null || !brokerId.equals(resourceBrokerId))) {
            throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
        }
    }

    /** Requires a broker mapping even when the caller has an administrator role. */
    public static long requireBrokerIdentity() {
        if (AuthzRequestScope.present()) return AuthzRequestScope.requireBrokerIdentity();
        if (McpAuthorizationScope.current() != null) {
            return requireBrokerIdentity(McpAuthorizationScope.requireCurrent().context());
        }
        if (A2aCredentialContext.snapshot() != null) {
            A2aCredentialContext.Lease lease = A2aCredentialContext.required();
            AuthorizationContext context = lease.authorizationContext();
            if (context == null) throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
            return requireBrokerIdentity(context);
        }
        throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
    }

    private static long requireBrokerIdentity(AuthorizationContext context) {
        Long brokerId = context.businessIdentities().get("BROKER");
        if (brokerId == null) throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
        return brokerId;
    }
}
