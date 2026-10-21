package com.bkanent.common.memory;

import java.io.Serializable;
import java.util.List;
import java.time.Instant;

/** One page of experiences with selection scores. */
public record MemoryExperienceQueryResponse(List<MemoryExperienceMatch> matches, String nextCursor, boolean hasMore,
        String scoringVersion, Instant asOf, String matterId, Long matterRevision, String objectiveHash,
        int usedTokens, boolean budgetLimited) implements Serializable {
    private static final long serialVersionUID = 1L;

    public MemoryExperienceQueryResponse {
        matches = MemoryContract.unique(matches, MemoryException.Field.EVIDENCE);
        nextCursor = MemoryContract.optionalId(nextCursor, MemoryException.Field.CURSOR);
        MemoryV2Contract.requirePage(nextCursor, hasMore);
        scoringVersion = MemoryContract.id(scoringVersion, MemoryException.Field.VERSION);
        MemoryContract.required(asOf, MemoryException.Field.SCORE);
        matterId = MemoryContract.optionalId(matterId, MemoryException.Field.ASSOCIATION);
        MemoryContract.require(matterRevision == null || matterRevision > 0, MemoryException.Field.VERSION);
        MemoryContract.require(objectiveHash != null && objectiveHash.matches("[a-f0-9]{64}"), MemoryException.Field.HASH);
        MemoryContract.require(usedTokens >= 0, MemoryException.Field.PAGE);
        MemoryContract.require(matterId != null || matterRevision == null, MemoryException.Field.ASSOCIATION);
    }

    /** Compatibility constructor retained for contract fixtures that describe an empty page. */
    public MemoryExperienceQueryResponse(List<?> ignored, String nextCursor, boolean hasMore) {
        this(List.of(), nextCursor, hasMore, "experience-selection-v1", Instant.EPOCH, null, null,
                "0".repeat(64), 0, false);
        if (!ignored.isEmpty()) throw new MemoryException(MemoryErrorCode.INVALID_ARGUMENT, MemoryException.Field.EVIDENCE);
    }
}
