package com.bkanent.common.memory;

import java.io.Serializable;
import java.util.List;

/** One page of matters; a page claiming more entries carries the next cursor. */
public record MemoryMatterQueryResponse(List<MemoryMatter> matters, String nextCursor, boolean hasMore)
        implements Serializable {
    private static final long serialVersionUID = 1L;

    public MemoryMatterQueryResponse {
        matters = MemoryContract.list(matters, MemoryException.Field.ASSOCIATION);
        nextCursor = MemoryContract.optionalId(nextCursor, MemoryException.Field.CURSOR);
        MemoryV2Contract.requirePage(nextCursor, hasMore);
    }
}
