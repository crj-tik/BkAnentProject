package com.bkanent.common.rag;

import java.io.Serializable;

/** Inquiry listing selector; the listingId is a business target, never an identity. */
public record RagListingInquiryVerifyRequest(int contractVersion, String requestId, Long listingId)
        implements Serializable {
    private static final long serialVersionUID = 1L;

    public RagListingInquiryVerifyRequest {
        RagContract.version(contractVersion);
        RagContract.text(requestId, "requestId");
        RagContract.require(listingId != null && listingId > 0, "listingId");
    }
}
