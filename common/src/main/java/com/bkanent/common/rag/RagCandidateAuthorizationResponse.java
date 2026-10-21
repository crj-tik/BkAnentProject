package com.bkanent.common.rag;

import java.io.Serializable;
import java.util.HashSet;
import java.util.List;

/** Only authorized document IDs are returned; exclusions do not reveal why a candidate failed. */
public record RagCandidateAuthorizationResponse(int contractVersion, String requestId,
        List<String> authorizedDocumentIds) implements Serializable {
    private static final long serialVersionUID = 1L;
    public RagCandidateAuthorizationResponse {
        RagContract.version(contractVersion);
        requestId = RagContract.text(requestId, "requestId");
        authorizedDocumentIds = RagContract.list(authorizedDocumentIds);
        RagContract.require(authorizedDocumentIds.size() <= 500, "authorizedDocumentIds");
        HashSet<String> ids = new HashSet<>();
        authorizedDocumentIds.forEach(id -> RagContract.require(ids.add(RagContract.text(id, "authorizedDocumentIds")), "authorizedDocumentIds"));
    }
    @Override public String toString() { return "RagCandidateAuthorizationResponse[REDACTED]"; }
}
