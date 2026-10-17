package com.bkanent.common.memory;

import java.io.Serializable;
import java.util.List;

/** One page of preference blocks with their current states. */
public record MemoryPreferenceQueryResponse(List<PreferenceBlock> blocks, String nextCursor, boolean hasMore)
        implements Serializable {
    private static final long serialVersionUID = 1L;

    public MemoryPreferenceQueryResponse {
        blocks = MemoryContract.list(blocks, MemoryException.Field.PREFERENCE);
        nextCursor = MemoryContract.optionalId(nextCursor, MemoryException.Field.CURSOR);
        MemoryV2Contract.requirePage(nextCursor, hasMore);
    }
}
