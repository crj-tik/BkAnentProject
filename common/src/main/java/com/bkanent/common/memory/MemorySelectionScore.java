package com.bkanent.common.memory;

import java.io.Serializable;
import java.time.Instant;

/** Independent selection dimensions at one asOf; scoring is not an observation or authorization. */
public record MemorySelectionScore(double relevance, double confidence, double activation, double timeWeight,
                                   Instant asOf, String scoringVersion) implements Serializable {
    private static final long serialVersionUID = 1L;
    public MemorySelectionScore {
        MemoryContract.unit(relevance);
        MemoryContract.unit(confidence);
        MemoryContract.unit(activation);
        MemoryContract.unit(timeWeight);
        MemoryContract.required(asOf, MemoryException.Field.SCORE);
        scoringVersion = MemoryContract.id(scoringVersion, MemoryException.Field.VERSION);
    }
}
