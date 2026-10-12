package com.bkanent.common.rag;

import java.io.Serializable;
import java.util.List;

public record RagSearchResponse(int contractVersion, String requestId, String spaceId, String strategyVersion, RagSearchMode requestedMode, RagSearchMode effectiveMode, boolean degraded, List<RagStageFailure> failedStages, List<RagCandidate> candidates, List<RagStageStats> stageStats) implements Serializable {
    private static final long serialVersionUID = 1L;

    public RagSearchResponse {
        failedStages = RagContract.list(failedStages);
        candidates = RagContract.list(candidates);
        stageStats = RagContract.list(stageStats);
    }

    @Override
    public String toString() { return "RagSearchResponse[REDACTED]"; }
}
