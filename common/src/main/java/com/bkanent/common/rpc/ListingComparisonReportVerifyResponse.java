package com.bkanent.common.rpc;

import java.io.Serializable;

/**
 * Comparison report ownership verification result. ACCESSIBLE echoes the verified report id;
 * NOT_ACCESSIBLE never distinguishes missing and foreign-owned reports.
 */
public record ListingComparisonReportVerifyResponse(String requestId, String result, String reportId)
        implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final String ACCESSIBLE = "ACCESSIBLE";
    public static final String NOT_ACCESSIBLE = "NOT_ACCESSIBLE";

    public ListingComparisonReportVerifyResponse {
        if (result == null || !(ACCESSIBLE.equals(result) || NOT_ACCESSIBLE.equals(result))) {
            throw new IllegalArgumentException("Invalid comparison report verify result");
        }
        if (requestId == null || requestId.isBlank() || requestId.length() > 128) {
            throw new IllegalArgumentException("Invalid comparison report verify requestId");
        }
        if (ACCESSIBLE.equals(result)) {
            if (reportId == null || !reportId.matches("[0-9a-f]{32}")) {
                throw new IllegalArgumentException("Accessible comparison report requires reportId");
            }
        } else if (reportId != null) {
            throw new IllegalArgumentException("Inaccessible comparison report must not carry reportId");
        }
    }
}
