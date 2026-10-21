package com.bkanent.common.memory;

import java.io.Serializable;

public record MemoryExperienceMatch(MemoryExperience experience, MemoryExperienceScore score,
        int estimatedTokens) implements Serializable {
    private static final long serialVersionUID = 1L;
    public MemoryExperienceMatch {
        MemoryContract.required(experience, MemoryException.Field.EVIDENCE);
        MemoryContract.required(score, MemoryException.Field.SCORE);
        MemoryContract.require(estimatedTokens > 0, MemoryException.Field.PAGE);
    }
}
