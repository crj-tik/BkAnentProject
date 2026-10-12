package com.bkanent.common.rag;

import java.io.Serializable;

public record RagStageStats(RagStage stage, int candidateCount, long elapsedMillis) implements Serializable {
    private static final long serialVersionUID = 1L;

}
