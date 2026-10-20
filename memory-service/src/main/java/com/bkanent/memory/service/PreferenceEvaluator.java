package com.bkanent.memory.service;

import com.bkanent.common.memory.*;
import java.time.Instant;
import java.util.*;

/** No tools, user credential, scope selection or general memory search capability. */
public interface PreferenceEvaluator {
    String version();
    Result evaluate(Evidence evidence);
    record Evidence(MemoryMessage message, Instant acceptedAt, Set<String> dimensions) { }
    record Result(PreferenceTransition.Assessment assessment, List<MemoryPreferenceAssessment> candidates) {
        public Result { Objects.requireNonNull(assessment); candidates = List.copyOf(candidates); }
        String hash() { return MemoryCanonicalHash.contentHash(List.of(assessment, candidates)); }
    }
}
