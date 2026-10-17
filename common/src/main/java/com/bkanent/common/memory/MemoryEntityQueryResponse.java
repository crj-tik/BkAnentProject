package com.bkanent.common.memory;

import java.io.Serializable;
import java.util.List;

/** One page of entity anchors. */
public record MemoryEntityQueryResponse(List<MemoryEntityAnchor> entities, String nextCursor, boolean hasMore)
        implements Serializable {
    private static final long serialVersionUID = 1L;

    public MemoryEntityQueryResponse {
        entities = MemoryContract.list(entities, MemoryException.Field.SOURCE);
        nextCursor = MemoryContract.optionalId(nextCursor, MemoryException.Field.CURSOR);
        MemoryV2Contract.requirePage(nextCursor, hasMore);
    }
}
