package com.bkanent.common.memory;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/** Bounded selection; the objective itself is read from the authorized matter and is not echoed. */
public record MemoryPreferenceAssemblyResponse(String matterId, long matterRevision, String objectiveHash,
        Instant asOf, String scoringVersion, List<MemoryPreferenceMatch> matches, boolean budgetLimited)
        implements Serializable {
    private static final long serialVersionUID = 1L;
    public MemoryPreferenceAssemblyResponse {
        matterId = MemoryContract.id(matterId, MemoryException.Field.ASSOCIATION);
        MemoryContract.require(matterRevision > 0, MemoryException.Field.VERSION);
        MemoryContract.require(objectiveHash != null && objectiveHash.matches("[a-f0-9]{64}"), MemoryException.Field.HASH);
        MemoryContract.required(asOf, MemoryException.Field.SCORE);
        scoringVersion = MemoryContract.id(scoringVersion, MemoryException.Field.VERSION);
        matches = MemoryContract.unique(matches, MemoryException.Field.PREFERENCE);
        for (var match : matches) {
            MemoryContract.require(match.score().asOf().equals(asOf)
                    && match.score().scoringVersion().equals(scoringVersion)
                    && match.selection().matterId().equals(matterId)
                    && match.selection().matterRevision() == matterRevision, MemoryException.Field.SCORE);
        }
    }
    public List<MemoryPreferenceSelectionRef> selections() { return matches.stream().map(MemoryPreferenceMatch::selection).toList(); }
}
