package com.bkanent.common.rag;

import java.io.Serializable;

/** Legacy intent may be reconciled only against proven source revision/hash, never blindly replayed. */
public record RagListingReconcileRequest(int contractVersion, String requestId, Long listingId,
                                         long expectedRevision, String expectedContentHash,
                                         boolean deleteIntent) implements Serializable {
    private static final long serialVersionUID = 1L;

    public RagListingReconcileRequest {
        RagContract.version(contractVersion);
        RagContract.text(requestId, "requestId");
        if (listingId == null || listingId <= 0 || expectedRevision <= 0 || expectedContentHash == null
                || !expectedContentHash.matches("[0-9a-f]{64}")) {
            throw new RagException(RagErrorCode.LEGACY_RECONCILIATION_REQUIRED);
        }
    }
}
