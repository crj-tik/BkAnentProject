package com.bkanent.common.authz;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** Stable user-version facts. No issued time or storage TTL; never send to a model or log. */
public record AuthorizationSnapshot(String actorId, long permissionRevision, long catalogVersion,
                                    boolean businessAdmin, List<String> permissions,
                                    Long grantDeadlineEpochSecond, String algorithm, String keyId,
                                    String signature) implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    public AuthorizationSnapshot {
        actorId = AuthzContract.actor(actorId);
        AuthzContract.require(permissionRevision > 0 && catalogVersion > 0);
        permissions = AuthzContract.permissions(permissions);
        AuthzContract.require(grantDeadlineEpochSecond == null || grantDeadlineEpochSecond > 0);
        AuthzContract.signature(algorithm, keyId, signature);
    }

    @Override public String toString() { return "AuthorizationSnapshot[REDACTED]"; }
}
