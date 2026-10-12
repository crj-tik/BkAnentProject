package com.bkanent.common.rag;

import java.io.Serializable;
import java.util.List;

public record RagListingSearchResponse(int contractVersion, String requestId, List<RagListingMatch> matches,
                                       String context, RagSearchResponse retrieval) implements Serializable {
    private static final long serialVersionUID = 1L;

    public RagListingSearchResponse {
        RagContract.version(contractVersion);
        matches = RagContract.list(matches);
    }

    @Override
    public String toString() { return "RagListingSearchResponse[REDACTED]"; }
}
