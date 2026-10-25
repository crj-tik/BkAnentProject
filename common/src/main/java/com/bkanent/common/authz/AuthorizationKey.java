package com.bkanent.common.authz;

/** Stable cache identity only; never sufficient to authenticate or resume a run. */
public record AuthorizationKey(String actorId, long permissionRevision) {
    public AuthorizationKey {
        actorId = AuthzContract.actor(actorId);
        AuthzContract.require(permissionRevision > 0);
    }

    public static AuthorizationKey of(AuthorizationSnapshot snapshot) {
        AuthzContract.require(snapshot != null);
        return new AuthorizationKey(snapshot.actorId(), snapshot.permissionRevision());
    }

    @Override public String toString() { return "AuthorizationKey[REDACTED]"; }
}
