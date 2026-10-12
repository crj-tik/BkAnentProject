package com.bkanent.common.rag;

import java.io.Serializable;

public record RagInspectRequest(int contractVersion, String requestId, String spaceId) implements Serializable {
    private static final long serialVersionUID = 1L;

    public RagInspectRequest {
        RagContract.version(contractVersion);
        RagContract.text(requestId, "requestId");
        RagContract.text(spaceId, "spaceId");
    }
}
