package com.bkanent.common.rag;

import java.io.Serializable;
import java.util.List;

public record MemoryRagSearchResponse(RagSearchResponse retrieval, List<MemoryRagMatch> matches,
                                      String domainScoringVersion) implements Serializable {
    private static final long serialVersionUID = 1L;

    public MemoryRagSearchResponse { matches = RagContract.list(matches); }

    @Override
    public String toString() { return "MemoryRagSearchResponse[REDACTED]"; }
}
