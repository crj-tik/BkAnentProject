package com.bkanent.common.rag;

import java.io.Serializable;

public record RagStatusRequest(int contractVersion, String requestId, String spaceId, String eventId,
                               String documentId) implements Serializable {
    private static final long serialVersionUID = 1L;

    public RagStatusRequest {
        RagContract.version(contractVersion);
        RagContract.text(requestId, "requestId");
        RagContract.text(spaceId, "spaceId");
        RagContract.selector(eventId, documentId);
    }
}
