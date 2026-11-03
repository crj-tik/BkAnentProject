package com.bkanent.common.rag;

import com.bkanent.common.authz.AuthzFreshness;
import com.bkanent.common.authz.AuthorizationContext;
import com.bkanent.common.listing.ListingSnapshotBatchReadRequest;

import java.io.Serializable;

/** Listing business snapshot read bound to a short lived Auth context and a caller HMAC. */
public record RagContextAuthorizedListingBusinessBatchReadRequest(
        int contractVersion,
        String requestId,
        ListingSnapshotBatchReadRequest request,
        AuthorizationContext authorizationContext,
        AuthzFreshness freshness) implements Serializable {
    private static final long serialVersionUID = 1L;

    public RagContextAuthorizedListingBusinessBatchReadRequest {
        RagContract.version(contractVersion);
        RagContract.require(requestId != null && requestId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"), "requestId");
        RagContract.require(request != null, "request");
        RagContract.require(authorizationContext != null && freshness != null, "authorization");
    }
}
