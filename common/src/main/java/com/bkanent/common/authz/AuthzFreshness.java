package com.bkanent.common.authz;

import java.io.Serial;
import java.io.Serializable;

/** checkedAt is the start of the real authority read, never a cache GET/publication time. */
public record AuthzFreshness(String userId, long permissionRevision, long credentialEpoch,
                             AuthorizationStatus status, String permissionHash,
                             long checkedAtEpochSecond, long notAfterEpochSecond, String nonce,
                             String algorithm, String keyId, String signature) implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    public AuthzFreshness {
        userId = AuthzContract.actor(userId);
        AuthzContract.require(permissionRevision > 0 && credentialEpoch >= 0 && status != null);
        permissionHash = AuthzContract.hash(permissionHash);
        AuthzContract.period(checkedAtEpochSecond, notAfterEpochSecond, AuthzDeadlines.FRESHNESS_SECONDS);
        nonce = AuthzContract.nonce(nonce);
        AuthzContract.signature(algorithm, keyId, signature);
    }

    @Override public String toString() { return "AuthzFreshness[REDACTED]"; }
}
