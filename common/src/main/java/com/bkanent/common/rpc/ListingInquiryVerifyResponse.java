package com.bkanent.common.rpc;

import java.io.Serializable;

/**
 * Inquiry listing verification result. ACCESSIBLE carries the current on-sale status and row version
 * for post-commit recheck; NOT_ACCESSIBLE never distinguishes missing, offline and unauthorized listings.
 */
public record ListingInquiryVerifyResponse(String requestId, String result, Long listingId,
                                           String status, String listingVersion) implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final String ACCESSIBLE = "ACCESSIBLE";
    public static final String NOT_ACCESSIBLE = "NOT_ACCESSIBLE";

    public ListingInquiryVerifyResponse {
        if (result == null || !(ACCESSIBLE.equals(result) || NOT_ACCESSIBLE.equals(result))) {
            throw new IllegalArgumentException("Invalid listing inquiry verify result");
        }
        if (requestId == null || requestId.isBlank() || requestId.length() > 128) {
            throw new IllegalArgumentException("Invalid listing inquiry verify requestId");
        }
        if (ACCESSIBLE.equals(result)) {
            if (listingId == null || listingId <= 0 || status == null || status.isBlank()
                    || listingVersion == null || listingVersion.isBlank()) {
                throw new IllegalArgumentException("Accessible listing inquiry requires status and version");
            }
        } else if (listingId != null || status != null || listingVersion != null) {
            throw new IllegalArgumentException("Inaccessible listing inquiry must not carry listing facts");
        }
    }
}
