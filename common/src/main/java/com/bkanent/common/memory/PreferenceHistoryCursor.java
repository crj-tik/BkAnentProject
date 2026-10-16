package com.bkanent.common.memory;

import java.io.Serializable;

/** Fixed block/content/authorization view and block-local keyset position, never a source sequence. */
public record PreferenceHistoryCursor(String blockId, long revision, long authorizationRevision,
                                      long afterOrdinal) implements Serializable {
    private static final long serialVersionUID = 1L;
    public PreferenceHistoryCursor {
        blockId = MemoryContract.id(blockId, MemoryException.Field.CURSOR);
        MemoryContract.require(revision > 0 && authorizationRevision > 0 && afterOrdinal >= 0, MemoryException.Field.CURSOR);
    }
}
