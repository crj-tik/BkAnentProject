package com.bkanent.knowledge.generation;

import com.bkanent.common.knowledge.KnowledgeBody;

import java.util.List;

public record KnowledgeCandidate(KnowledgeBody body, String summary, List<CandidateEvidence> evidence) {
    public KnowledgeCandidate {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
    @Override public String toString() { return "KnowledgeCandidate[body=REDACTED,evidenceCount=" + evidence.size() + "]"; }
}
