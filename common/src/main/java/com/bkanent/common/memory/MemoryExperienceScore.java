package com.bkanent.common.memory;

import java.io.Serializable;
import java.time.Instant;

/** Request-specific ranking inputs; scoring never changes stored evidence or experience state. */
public record MemoryExperienceScore(double relevance, double confidence, double activation,
        double timeWeight, double score, Instant asOf, String scoringVersion) implements Serializable {
    private static final long serialVersionUID = 1L;
    public MemoryExperienceScore {
        MemoryContract.unit(relevance); MemoryContract.unit(confidence); MemoryContract.unit(activation);
        MemoryContract.unit(timeWeight); MemoryContract.unit(score);
        MemoryContract.required(asOf, MemoryException.Field.SCORE);
        scoringVersion = MemoryContract.id(scoringVersion, MemoryException.Field.VERSION);
    }
}
