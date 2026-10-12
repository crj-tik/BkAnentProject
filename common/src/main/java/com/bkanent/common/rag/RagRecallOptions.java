package com.bkanent.common.rag;

import java.io.Serializable;

public record RagRecallOptions(Boolean hybrid, RagRecallMode singleRecall,
                               Integer keywordCandidates, Integer vectorCandidates) implements Serializable {
    private static final long serialVersionUID = 1L;

    public RagRecallOptions normalized() {
        boolean both = hybrid == null || hybrid;
        RagContract.require(both ? singleRecall == null : singleRecall != null, "recall.singleRecall");
        boolean keyword = both || singleRecall == RagRecallMode.KEYWORD;
        boolean vector = both || singleRecall == RagRecallMode.VECTOR;
        RagContract.require(keyword || keywordCandidates == null, "recall.keywordCandidates");
        RagContract.require(vector || vectorCandidates == null, "recall.vectorCandidates");
        Integer k = keyword ? (keywordCandidates == null ? 100 : keywordCandidates) : null;
        Integer v = vector ? (vectorCandidates == null ? 100 : vectorCandidates) : null;
        if (k != null) RagContract.require(k >= 1 && k <= 500, "recall.keywordCandidates");
        if (v != null) RagContract.require(v >= 1 && v <= 500, "recall.vectorCandidates");
        return new RagRecallOptions(both, singleRecall, k, v);
    }

    public int capacity() {
        RagRecallOptions normalized = normalized();
        return (normalized.keywordCandidates == null ? 0 : normalized.keywordCandidates)
                + (normalized.vectorCandidates == null ? 0 : normalized.vectorCandidates);
    }
}
