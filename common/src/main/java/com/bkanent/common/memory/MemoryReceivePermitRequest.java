package com.bkanent.common.memory;

import java.io.Serializable;
import java.util.List;

/** Server-issuable receive permission request for exact events, per D2 reliable delivery. */
public record MemoryReceivePermitRequest(MemoryRequestEnvelope envelope, MemoryContextKey contextKey,
                                          List<EventSubmission> events) implements Serializable, MemoryV2Request {
    private static final long serialVersionUID = 1L;

    /** One exact event: identity, content hash and purpose bound before acceptance. */
    public record EventSubmission(String eventId, String contentHash, String purpose) implements Serializable {
        private static final long serialVersionUID = 1L;

        public EventSubmission {
            eventId = MemoryContract.id(eventId, MemoryException.Field.SOURCE);
            MemoryContract.require(MemoryV2Contract.validHash(contentHash), MemoryException.Field.HASH);
            purpose = MemoryContract.text(purpose, MemoryException.Field.TEXT);
        }
    }

    public MemoryReceivePermitRequest {
        MemoryV2Contract.requireOperation(envelope, MemoryV2Contract.OPERATION_RECEIVE_PERMIT);
        MemoryContract.required(contextKey, MemoryException.Field.IDENTITY);
        events = MemoryContract.nonempty(events, MemoryException.Field.SOURCE);
    }
}
