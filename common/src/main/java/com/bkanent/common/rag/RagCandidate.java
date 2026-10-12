package com.bkanent.common.rag;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

public record RagCandidate(String documentId, String sourceType, String sourceId, String content, long projectionVersion, String schemaVersion, List<RagRecallMode> sources, Double keywordScore, Double vectorScore, Double fusionScore, Double rawRerankScore, Double relevanceScore, RagScoreKind scoreKind, String scoringVersion, Map<String, RagValue> metadata) implements Serializable {
    private static final long serialVersionUID = 1L;

    public RagCandidate {
        sources = RagContract.list(sources);
        metadata = RagContract.attributes(metadata);
        for (Double score : new Double[]{keywordScore, vectorScore, fusionScore, rawRerankScore, relevanceScore}) {
            RagContract.require(score == null || Double.isFinite(score), "score");
        }
        RagContract.require(relevanceScore == null || relevanceScore >= 0 && relevanceScore <= 1, "relevanceScore");
    }

    @Override
    public String toString() { return "RagCandidate[REDACTED]"; }
}
