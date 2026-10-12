package com.bkanent.common.rag;

import java.io.Serializable;
import java.time.Instant;

public record RagIndexEvent(int contractVersion, String requestId, String eventId, String spaceId,
                            String documentId, long revision, String schemaVersion, RagIndexOperation operation,
                            String contentHash, RagDocument document, Instant createdAt) implements Serializable {
    private static final long serialVersionUID = 1L;

    public RagIndexEvent {
        RagContract.version(contractVersion);
        RagContract.text(requestId, "requestId");
        RagContract.text(eventId, "eventId");
        RagContract.text(spaceId, "spaceId");
        RagContract.text(documentId, "documentId");
        RagContract.text(schemaVersion, "schemaVersion");
        RagContract.require(revision > 0 && createdAt != null, "revision");
        RagContract.require(operation != null && (operation == RagIndexOperation.UPSERT ? document != null : document == null), "document");
        RagContract.require(contentHash != null && contentHash.matches("[0-9a-f]{64}"), "contentHash");
    }

    @Override
    public String toString() { return "RagIndexEvent[REDACTED]"; }
}
