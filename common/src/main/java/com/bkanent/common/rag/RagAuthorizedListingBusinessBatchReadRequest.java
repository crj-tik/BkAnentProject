package com.bkanent.common.rag;

import com.bkanent.common.listing.ListingSnapshotBatchReadRequest;

import java.io.Serializable;

/** Internal source batch read bound to the caller's current Auth response and an exact signed request. */
public record RagAuthorizedListingBusinessBatchReadRequest(int contractVersion, String requestId,
                                                           ListingSnapshotBatchReadRequest request,
                                                           String authorizationEvidenceHash) implements Serializable {
    private static final long serialVersionUID = 1L;
    public RagAuthorizedListingBusinessBatchReadRequest {
        RagContract.version(contractVersion);
        RagContract.require(requestId != null && requestId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"), "requestId");
        RagContract.require(request != null, "request");
        RagContract.require(authorizationEvidenceHash != null && authorizationEvidenceHash.matches("[0-9a-f]{64}"), "authorizationEvidenceHash");
    }
}
