package com.bkanent.common.authz;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/** All three components require signature/time verification before the caller may use them. */
public record AuthzResponse(AuthorizationSnapshot snapshot, AuthorizationContext context,
                            AuthzFreshness freshness) implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    public AuthzResponse {
        AuthzContract.require(snapshot != null && context != null && freshness != null);
        AuthzContract.check(snapshot.actorId().equals(context.actorId())
                && snapshot.actorId().equals(freshness.userId())
                && snapshot.permissionRevision() == context.permissionRevision()
                && snapshot.permissionRevision() == freshness.permissionRevision()
                && snapshot.businessIdentities().equals(context.businessIdentities())
                && context.credentialEpoch() == freshness.credentialEpoch()
                && context.permissionHash().equals(AuthzCanonicalHash.snapshotHash(snapshot))
                && context.permissionHash().equals(freshness.permissionHash())
                && snapshot.permissions().containsAll(
                        AuthzOperationPolicy.requiredPermissions(context.allowedOperations())),
                AuthzErrorCode.CONTEXT_CONFLICT);
        AuthzContract.check(freshness.status() == AuthorizationStatus.ACTIVE, AuthzErrorCode.AUTHENTICATION_FAILED);
        AuthzContract.check(snapshot.grantDeadlineEpochSecond() == null
                || context.expiresAtEpochSecond() <= snapshot.grantDeadlineEpochSecond(), AuthzErrorCode.CONTEXT_CONFLICT);
        AuthzContract.check(Objects.equals(context.algorithm(), freshness.algorithm()), AuthzErrorCode.CONTEXT_CONFLICT);
    }

    @Override public String toString() { return "AuthzResponse[REDACTED]"; }
}
