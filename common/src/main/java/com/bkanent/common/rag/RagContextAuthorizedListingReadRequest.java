package com.bkanent.common.rag;

import com.bkanent.common.authz.AuthzFreshness;
import com.bkanent.common.authz.AuthorizationContext;

import java.io.Serializable;

/** One Listing summary read bound to an Auth-signed execution context and the caller's HMAC proof. */
public record RagContextAuthorizedListingReadRequest(int contractVersion, RagListingReadRequest request,
                                                     AuthorizationContext authorizationContext,
                                                     AuthzFreshness freshness) implements Serializable {
    private static final long serialVersionUID = 1L;

    public RagContextAuthorizedListingReadRequest {
        RagContract.version(contractVersion);
        RagContract.require(request != null, "request");
        RagContract.require(request.listingId() != null, "listingId");
        RagContract.require(authorizationContext != null && freshness != null, "authorization");
        RagContract.require(authorizationContext.runId() != null && authorizationContext.sessionId() != null,
                "executionBinding");
    }
}
