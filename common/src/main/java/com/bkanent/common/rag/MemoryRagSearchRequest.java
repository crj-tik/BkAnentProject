package com.bkanent.common.rag;

import java.io.Serializable;

public record MemoryRagSearchRequest(RagSearchRequest search, int topK, Double minimumSemanticRelevance,
                                     Double halfLifeDays) implements Serializable {
    private static final long serialVersionUID = 1L;

    public MemoryRagSearchRequest {
        RagContract.require(search != null && topK >= 1 && topK <= 200, "topK");
        RagContract.require(minimumSemanticRelevance == null || Double.isFinite(minimumSemanticRelevance)
                && minimumSemanticRelevance >= 0 && minimumSemanticRelevance <= 1, "minimumSemanticRelevance");
        RagContract.require(halfLifeDays == null || Double.isFinite(halfLifeDays) && halfLifeDays > 0, "halfLifeDays");
    }

    @Override
    public String toString() { return "MemoryRagSearchRequest[REDACTED]"; }
}
