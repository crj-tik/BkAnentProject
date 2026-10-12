package com.bkanent.common.rag;

import java.io.Serializable;

/** Logical managed target only; no index, collection, endpoint or arbitrary schema definition. */
public record RagProvisionRequest(int contractVersion, String requestId, String spaceId, String schemaVersion,
                                  int dimension) implements Serializable {
    private static final long serialVersionUID = 1L;

    public RagProvisionRequest {
        RagContract.version(contractVersion);
        RagContract.text(requestId, "requestId");
        RagContract.text(spaceId, "spaceId");
        RagContract.text(schemaVersion, "schemaVersion");
        RagContract.require(dimension > 0, "dimension");
    }
}
