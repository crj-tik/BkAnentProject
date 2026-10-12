package com.bkanent.common.rag;

import java.io.Serializable;

public record RagIndexStatus(int contractVersion, String requestId, String eventId, String spaceId, String documentId, long revision, String contentHash, RagIndexState state, long latestAcceptedRevision, boolean tombstone, RagBackendStatus keyword, RagBackendStatus vector, RagErrorCode errorCode) implements Serializable {
    private static final long serialVersionUID = 1L;

}
