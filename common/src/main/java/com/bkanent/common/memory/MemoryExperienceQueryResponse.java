package com.bkanent.common.memory;

import java.io.Serializable;
import java.util.List;

/** One page of experiences with selection scores. */
public record MemoryExperienceQueryResponse(List<MemoryExperience> experiences, String nextCursor, boolean hasMore)
        implements Serializable {
    private static final long serialVersionUID = 1L;

    public MemoryExperienceQueryResponse {
        experiences = MemoryContract.list(experiences, MemoryException.Field.EVIDENCE);
        nextCursor = MemoryContract.optionalId(nextCursor, MemoryException.Field.CURSOR);
        MemoryV2Contract.requirePage(nextCursor, hasMore);
    }
}
