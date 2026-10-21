package com.bkanent.common.rag;

import java.io.Serializable;

/** Metadata-only candidate sent to Memory for a current truth/grant check; never carries body text. */
public record RagCandidateReference(String documentId, String sourceType, String sourceId,
        long projectionVersion, String schemaVersion, String contentHash) implements Serializable {
    private static final long serialVersionUID = 1L;
    public RagCandidateReference {
        documentId = RagContract.text(documentId, "documentId");
        sourceType = RagContract.text(sourceType, "sourceType");
        sourceId = RagContract.text(sourceId, "sourceId");
        RagContract.require(projectionVersion > 0, "projectionVersion");
        schemaVersion = RagContract.text(schemaVersion, "schemaVersion");
        RagSignatureSupport.hash(contentHash);
    }
    @Override public String toString() { return "RagCandidateReference[REDACTED]"; }
}
