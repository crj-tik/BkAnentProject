package com.bkanent.common.rag;

import com.bkanent.common.authz.AuthzFreshness;
import com.bkanent.common.authz.AuthorizationContext;

import java.io.Serializable;

/** Exact Listing asset-write ownership check bound to an Auth-signed execution context. */
public record RagContextAuthorizedListingAssetWriteRequest(int contractVersion, RagListingReadRequest request,
                                                           AuthorizationContext authorizationContext,
                                                           AuthzFreshness freshness) implements Serializable {
    private static final long serialVersionUID = 1L;

    public RagContextAuthorizedListingAssetWriteRequest {
        RagContract.version(contractVersion);
        RagContract.require(request != null && request.listingId() != null, "request");
        RagContract.require(authorizationContext != null && freshness != null, "authorization");
        RagContract.require(authorizationContext.runId() != null && authorizationContext.sessionId() != null,
                "executionBinding");
    }
}
