package com.bkanent.common.memory;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/** Bounded page of currently approved domain generalizations, without private citations. */
public record MemoryDomainExperienceQueryResponse(List<MemoryDomainExperience> experiences,
        String nextCursor, boolean hasMore, Instant asOf) implements Serializable {
    private static final long serialVersionUID = 1L;

    public MemoryDomainExperienceQueryResponse {
        experiences = MemoryContract.list(experiences, MemoryException.Field.PAGE);
        nextCursor = MemoryContract.optionalId(nextCursor, MemoryException.Field.CURSOR);
        MemoryV2Contract.requirePage(nextCursor, hasMore);
        MemoryContract.required(asOf, MemoryException.Field.EVIDENCE);
    }
}
