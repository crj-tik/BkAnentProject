package com.bkanent.common.rag;

import java.io.Serializable;
import java.time.Instant;

public record RagBackendStatus(long appliedRevision, int attempts, RagErrorCode errorCode, Instant nextRetryAt) implements Serializable {
    private static final long serialVersionUID = 1L;

}
