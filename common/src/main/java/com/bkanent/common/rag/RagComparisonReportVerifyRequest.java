package com.bkanent.common.rag;

import java.io.Serializable;

/** Comparison report selector; the reportId is a business target, never an identity. */
public record RagComparisonReportVerifyRequest(int contractVersion, String requestId, String reportId)
        implements Serializable {
    private static final long serialVersionUID = 1L;

    public RagComparisonReportVerifyRequest {
        RagContract.version(contractVersion);
        RagContract.text(requestId, "requestId");
        RagContract.require(reportId != null && reportId.matches("[0-9a-f]{32}"), "reportId");
    }
}
