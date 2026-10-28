package com.bkanent.common.a2a;

import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.authz.AuthzRequestScope;
import com.bkanent.common.mcp.authz.McpAuthorizationScope;
import com.bkanent.common.authz.AuthorizationContext;
import com.bkanent.common.authz.AuthzFreshness;
import com.bkanent.common.authz.AuthzOperationPolicy;
import com.bkanent.common.authz.AuthzResponse;

import java.time.Instant;

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

    /** Selects actor-owned or explicit all-task access; report task ids are never identities. */
    public static String requireActorScope(String ownOperation, String businessWideOperation) {
        if (AuthzRequestScope.present()) {
            if (AuthzRequestScope.businessAdmin()) {
                AuthzRequestScope.requireOperation(businessWideOperation);
                return null;
            }
            AuthzRequestScope.requireOperation(ownOperation);
            return AuthzRequestScope.requireCurrent().context().actorId();
        }
        if (McpAuthorizationScope.current() != null) {
            AuthorizationContext context = McpAuthorizationScope.requireCurrent().context();
            if (context.allowedOperations().contains(businessWideOperation)) {
                McpAuthorizationScope.requireContextPermission(businessWideOperation);
                return null;
            }
            McpAuthorizationScope.requireOperation(ownOperation);
            return context.actorId();
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
            return context.actorId();
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

    /** Returns the authenticated user id, never a model or request-body identity. */
    public static long requireActorId() {
        try {
            return Long.parseLong(requireActorIdentifier());
        } catch (NumberFormatException failure) {
            throw new AuthzException(AuthzErrorCode.AUTHENTICATION_FAILED);
        }
    }

    /** Returns a locally verified context and freshness pair for a bounded asynchronous permit. */
    public static VerifiedContext requireVerifiedContext() {
        if (AuthzRequestScope.present()) {
            AuthzResponse response = AuthzRequestScope.requireCurrent();
            return verified(response.context(), response.freshness());
        }
        if (McpAuthorizationScope.current() != null) {
            var request = McpAuthorizationScope.requireCurrent();
            return verified(request.context(), request.freshness());
        }
        if (A2aCredentialContext.snapshot() != null) {
            A2aCredentialContext.Lease lease = A2aCredentialContext.required();
            if (!lease.versioned() || lease.authorizationContext() == null || lease.freshness() == null) {
                throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
            }
            return verified(lease.authorizationContext(), lease.freshness());
        }
        throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
    }

    /** Public capability reads may omit a user context; an invalid present context still fails closed. */
    public static VerifiedContext currentVerifiedContextOrNull() {
        if (!AuthzRequestScope.present() && McpAuthorizationScope.current() == null
                && A2aCredentialContext.snapshot() == null) return null;
        return requireVerifiedContext();
    }

    public static boolean hasOperation(String operation) {
        VerifiedContext verified = currentVerifiedContextOrNull();
        if (verified == null || operation == null || !verified.context().allowedOperations().contains(operation)) {
            return false;
        }
        if (AuthzRequestScope.present()) {
            var response = AuthzRequestScope.requireCurrent();
            return AuthzOperationPolicy.allows(operation, response.context().allowedOperations(),
                    response.snapshot().permissions());
        }
        return true;
    }

    private static String requireActorIdentifier() {
        if (AuthzRequestScope.present()) return AuthzRequestScope.requireCurrent().context().actorId();
        if (McpAuthorizationScope.current() != null) return McpAuthorizationScope.requireCurrent().actorId();
        if (A2aCredentialContext.snapshot() != null) {
            A2aCredentialContext.Lease lease = A2aCredentialContext.required();
            if (lease.versioned()) return lease.authorizationContext().actorId();
            return lease.binding.actorId();
        }
        throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
    }

    private static VerifiedContext verified(AuthorizationContext context, AuthzFreshness freshness) {
        if (context == null || freshness == null || !context.actorId().equals(freshness.userId())
                || context.permissionRevision() != freshness.permissionRevision()
                || context.credentialEpoch() != freshness.credentialEpoch()
                || !context.permissionHash().equals(freshness.permissionHash())
                || freshness.status() != com.bkanent.common.authz.AuthorizationStatus.ACTIVE) {
            throw new AuthzException(AuthzErrorCode.CONTEXT_CONFLICT);
        }
        long now = Instant.now().getEpochSecond();
        long expiresAt = Math.min(context.expiresAtEpochSecond(), Math.min(context.runHardDeadlineEpochSecond(),
                freshness.notAfterEpochSecond()));
        if (now >= expiresAt) throw new AuthzException(AuthzErrorCode.EXPIRED);
        return new VerifiedContext(context.actorId(), context, freshness, expiresAt);
    }

    public record VerifiedContext(String actorId, AuthorizationContext context,
                                  AuthzFreshness freshness, long expiresAtEpochSecond) {
        @Override public String toString() { return "VerifiedDomainContext[REDACTED]"; }
    }

    private static long requireBrokerIdentity(AuthorizationContext context) {
        Long brokerId = context.businessIdentities().get("BROKER");
        if (brokerId == null) throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
        return brokerId;
    }
}
