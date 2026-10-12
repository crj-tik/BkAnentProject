package com.bkanent.common.rag;

import java.io.Serializable;
import java.util.List;

public record RagSpaceInspection(int contractVersion, String requestId, String spaceId, String ownerDomain, String policyVersion, String schemaVersion, String embeddingModelVersion, int dimension, boolean ready, List<RagStageFailure> failedStages) implements Serializable {
    private static final long serialVersionUID = 1L;

    public RagSpaceInspection {
        failedStages = RagContract.list(failedStages);
    }
}
