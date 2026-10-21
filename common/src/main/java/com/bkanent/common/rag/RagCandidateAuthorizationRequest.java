package com.bkanent.common.rag;

import java.io.Serializable;
import java.util.HashSet;
import java.util.List;

/** Bounded callback request contains only candidate identity/version/hash and the original search. */
public record RagCandidateAuthorizationRequest(int contractVersion, RagSearchRequest search,
        List<RagCandidateReference> candidates) implements Serializable {
    private static final long serialVersionUID = 1L;
    public RagCandidateAuthorizationRequest {
        RagContract.version(contractVersion);
        RagContract.require(search != null, "search");
        candidates = RagContract.list(candidates);
        RagContract.require(!candidates.isEmpty() && candidates.size() <= 500, "candidates");
        HashSet<String> ids = new HashSet<>();
        candidates.forEach(candidate -> RagContract.require(ids.add(candidate.documentId()), "candidates"));
    }
    @Override public String toString() { return "RagCandidateAuthorizationRequest[REDACTED]"; }
}
