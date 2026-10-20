package com.bkanent.common.memory;

import java.io.Serializable;
import java.util.List;

/** Server-issuable receive permission request for exact events, per D2 reliable delivery. */
public record MemoryReceivePermitRequest(MemoryRequestEnvelope envelope, MemoryContextKey contextKey,
                                          List<EventSubmission> events, List<MemoryResultAdmission> resultAdmissions,
                                          MemoryWriteAuthority writer) implements Serializable, MemoryV2Request {
    private static final long serialVersionUID = 1L;

    public MemoryReceivePermitRequest(MemoryRequestEnvelope envelope, MemoryContextKey contextKey, List<EventSubmission> events) {
        this(envelope, contextKey, events, List.of(), null);
    }

    public MemoryReceivePermitRequest(MemoryRequestEnvelope envelope, MemoryContextKey contextKey,
                                       List<EventSubmission> events, List<MemoryResultAdmission> admissions) {
        this(envelope, contextKey, events, admissions, null);
    }

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
        events = MemoryContract.list(events, MemoryException.Field.SOURCE);
        resultAdmissions = MemoryContract.list(resultAdmissions, MemoryException.Field.SOURCE);
        MemoryContract.require(!events.isEmpty() || !resultAdmissions.isEmpty(), MemoryException.Field.SOURCE);
        MemoryContract.require(events.size() + resultAdmissions.size() <= MemoryContract.MAX_LIST, MemoryException.Field.SOURCE);
        java.util.Set<String> ids = new java.util.HashSet<>();
        events.forEach(event -> MemoryContract.require(ids.add(event.eventId()), MemoryException.Field.SOURCE));
        resultAdmissions.forEach(result -> MemoryContract.require(ids.add(result.resultEventId()), MemoryException.Field.SOURCE));
    }
}
