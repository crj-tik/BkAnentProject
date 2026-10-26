package com.bkanent.common.rag;

import java.io.Serializable;

/** Exact Listing asset-write ownership check bound to one already signed Auth response. */
public record RagAuthorizedListingAssetWriteRequest(int contractVersion, RagListingReadRequest request,
                                                    String authorizationEvidenceHash) implements Serializable {
    private static final long serialVersionUID = 1L;

    public RagAuthorizedListingAssetWriteRequest {
        RagContract.version(contractVersion);
        RagContract.require(request != null && request.listingId() != null, "request");
        RagContract.require(authorizationEvidenceHash != null
                && authorizationEvidenceHash.matches("[0-9a-f]{64}"), "authorizationEvidenceHash");
    }
}
