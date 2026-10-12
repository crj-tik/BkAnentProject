package com.bkanent.common.rag;

import java.io.Serializable;

public record RagFusionOptions(RagFusionStrategy strategy, Integer rrfK,
                               Double keywordWeight, Double vectorWeight) implements Serializable {
    private static final long serialVersionUID = 1L;

    public RagFusionOptions normalized() {
        RagFusionStrategy s = strategy == null ? RagFusionStrategy.RRF : strategy;
        int k = rrfK == null ? 60 : rrfK;
        double keyword = keywordWeight == null ? 1.0 : keywordWeight;
        double vector = vectorWeight == null ? 1.0 : vectorWeight;
        RagContract.require(s == RagFusionStrategy.RRF, "fusion.strategy");
        RagContract.require(k >= 1 && k <= 200, "fusion.rrfK");
        RagContract.require(Double.isFinite(keyword) && keyword >= 0.1 && keyword <= 10, "fusion.keywordWeight");
        RagContract.require(Double.isFinite(vector) && vector >= 0.1 && vector <= 10, "fusion.vectorWeight");
        return new RagFusionOptions(s, k, keyword, vector);
    }
}
