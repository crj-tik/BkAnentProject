package com.bkanent.common.rag;

import java.io.Serializable;
import java.math.BigDecimal;

/** Legacy listing quantities/filters only, without model-supplied identity or physical target. */
public record RagListingSearchRequest(int contractVersion, String requestId, String query, String region,
                                      String layout, BigDecimal minArea, BigDecimal maxArea,
                                      BigDecimal minTotalPrice, BigDecimal maxTotalPrice,
                                      Integer keywordTopK, Integer vectorTopK, Integer topK) implements Serializable {
    private static final long serialVersionUID = 1L;

    public RagListingSearchRequest {
        RagContract.version(contractVersion);
        RagContract.text(requestId, "requestId");
        RagContract.require(query != null && !query.isBlank() && query.length() <= 8192, "query");
    }

    @Override
    public String toString() { return "RagListingSearchRequest[REDACTED]"; }
}
