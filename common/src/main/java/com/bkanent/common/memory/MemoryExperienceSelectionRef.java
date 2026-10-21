package com.bkanent.common.memory;

import java.io.Serializable;

/** Body-free identity for revalidating an experience assembled for one matter. */
public record MemoryExperienceSelectionRef(String experienceId, MemoryRevision experienceVersion,
        String matterId, long matterRevision, String matterGoalHash, String scoringVersion) implements Serializable {
    private static final long serialVersionUID = 1L;

    public MemoryExperienceSelectionRef {
        experienceId = MemoryContract.id(experienceId, MemoryException.Field.EVIDENCE);
        MemoryContract.required(experienceVersion, MemoryException.Field.VERSION);
        matterId = MemoryContract.id(matterId, MemoryException.Field.ASSOCIATION);
        MemoryContract.require(matterRevision > 0, MemoryException.Field.VERSION);
        MemoryContract.require(matterGoalHash != null && matterGoalHash.matches("[a-f0-9]{64}"), MemoryException.Field.HASH);
        scoringVersion = MemoryContract.id(scoringVersion, MemoryException.Field.VERSION);
    }
}
