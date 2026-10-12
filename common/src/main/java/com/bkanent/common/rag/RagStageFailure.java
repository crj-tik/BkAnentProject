package com.bkanent.common.rag;

import java.io.Serializable;

public record RagStageFailure(RagStage stage, RagErrorCode code) implements Serializable {
    private static final long serialVersionUID = 1L;

}
