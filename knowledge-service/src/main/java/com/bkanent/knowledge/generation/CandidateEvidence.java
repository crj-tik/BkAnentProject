package com.bkanent.knowledge.generation;

public record CandidateEvidence(String assetId, String quote) {
    @Override public String toString() { return "CandidateEvidence[assetId=" + assetId + ", quote=REDACTED]"; }
}
