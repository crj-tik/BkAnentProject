package com.bkanent.common.rag;

import java.io.Serializable;

/** Signed SQL-summary read; exactly one selector, never an indexing/reconciliation proof. */
public record RagListingReadRequest(int contractVersion, String requestId, Long listingId,
                                    String keyword) implements Serializable {
    public RagListingReadRequest {
        RagContract.version(contractVersion);
        RagContract.text(requestId, "requestId");
        RagContract.require((listingId != null) != (keyword != null), "selector");
        if (listingId != null) RagContract.require(listingId > 0, "listingId");
        if (keyword != null) RagContract.require(keyword.length() <= 8192, "keyword");
    }
}
