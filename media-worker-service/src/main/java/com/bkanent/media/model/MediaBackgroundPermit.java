package com.bkanent.media.model;

import com.bkanent.common.authz.AuthzFreshness;
import com.bkanent.common.authz.AuthorizationContext;

import java.io.Serializable;

/** Auth-signed identity/scope plus a worker HMAC bound to one immutable task payload. */
public record MediaBackgroundPermit(String taskId, String requestHash, String ownerActorId, Long brokerId,
                                    Long listingId, long expiresAtEpochSecond,
                                    AuthorizationContext authorizationContext, AuthzFreshness freshness,
                                    String keyId, String signature) implements Serializable {
    private static final long serialVersionUID = 1L;
    @Override public String toString() { return "MediaBackgroundPermit[REDACTED]"; }
}
