package com.bkanent.common.rag;

import java.io.Serializable;

/** Internal Listing read request bound to one already signed Auth response. */
public record RagAuthorizedListingReadRequest(int contractVersion, RagListingReadRequest request,
                                              String authorizationEvidenceHash) implements Serializable {
    private static final long serialVersionUID = 1L;

    public RagAuthorizedListingReadRequest {
        RagContract.version(contractVersion);
        RagContract.require(request != null, "request");
        RagContract.require(authorizationEvidenceHash != null
                && authorizationEvidenceHash.matches("[0-9a-f]{64}"), "authorizationEvidenceHash");
    }
}
