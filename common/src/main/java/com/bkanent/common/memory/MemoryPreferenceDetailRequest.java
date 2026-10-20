package com.bkanent.common.memory;

import java.io.Serializable;
import java.time.Instant;

/** A fixed block revision, keyset position and explicit asOf. Missing cursor starts at the current revision. */
public record MemoryPreferenceDetailRequest(MemoryRequestEnvelope envelope, MemoryContextKey contextKey,
        String blockId, PreferenceHistoryCursor cursor, int limit, Instant asOf, String matterId) implements Serializable, MemoryV2Request {
    private static final long serialVersionUID = 1L;
    public MemoryPreferenceDetailRequest {
        MemoryV2Contract.requireOperation(envelope, MemoryV2Contract.OPERATION_PREFERENCE_DETAIL);
        MemoryContract.required(contextKey, MemoryException.Field.IDENTITY);
        blockId = MemoryContract.id(blockId, MemoryException.Field.PREFERENCE);
        MemoryContract.require(cursor == null || blockId.equals(cursor.blockId()), MemoryException.Field.CURSOR);
        limit = MemoryV2Contract.requireLimit(limit);
        MemoryContract.required(asOf, MemoryException.Field.PREFERENCE);
        matterId = MemoryContract.optionalId(matterId, MemoryException.Field.ASSOCIATION);
    }
}
