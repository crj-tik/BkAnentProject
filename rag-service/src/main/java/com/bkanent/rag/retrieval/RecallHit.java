package com.bkanent.rag.retrieval;

import com.bkanent.common.rag.RagCandidate;

/** Backend projection identity; the hash is control-plane evidence, not public metadata. */
public record RecallHit(RagCandidate candidate, String contentHash) {
}
