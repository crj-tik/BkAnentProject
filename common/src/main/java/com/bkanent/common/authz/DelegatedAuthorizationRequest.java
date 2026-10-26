package com.bkanent.common.authz;

import java.io.Serial;
import java.io.Serializable;

/** Signed parent authorization and one exact cross-service request proof. */
public record DelegatedAuthorizationRequest(AuthorizationContext context, AuthzFreshness freshness,
                                             DelegationBinding binding, CallerProof callerProof)
        implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    public DelegatedAuthorizationRequest {
        AuthzContract.require(context != null && freshness != null && binding != null && callerProof != null);
    }

    @Override public String toString() { return "DelegatedAuthorizationRequest[REDACTED]"; }
}
