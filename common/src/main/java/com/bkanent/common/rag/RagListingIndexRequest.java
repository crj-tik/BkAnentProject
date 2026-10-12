package com.bkanent.common.rag;

import java.io.Serializable;

/** Authorized management intent; the domain constructs a latest-source transactional outbox projection. */
public record RagListingIndexRequest(int contractVersion, String requestId, Long listingId) implements Serializable {
    private static final long serialVersionUID = 1L;

    public RagListingIndexRequest {
        RagContract.version(contractVersion);
        RagContract.text(requestId, "requestId");
        RagContract.require(listingId != null && listingId > 0, "listingId");
    }
}
