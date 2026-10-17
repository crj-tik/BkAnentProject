package com.bkanent.common.memory;

import java.io.Serializable;
import java.util.List;

/** Persisted, verifiable permission for the submitted events; not a bearer token. */
public record MemoryReceivePermitResponse(String permitId, List<EventAcceptance> acceptances)
        implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Per-event decision; a reason is optional and never leaks provider internals. */
    public record EventAcceptance(String eventId, boolean accepted, String reason) implements Serializable {
        private static final long serialVersionUID = 1L;

        public EventAcceptance {
            eventId = MemoryContract.id(eventId, MemoryException.Field.SOURCE);
            reason = MemoryContract.optionalText(reason, MemoryException.Field.TEXT);
        }
    }

    public MemoryReceivePermitResponse {
        permitId = MemoryContract.id(permitId, MemoryException.Field.GRANT);
        acceptances = MemoryContract.nonempty(acceptances, MemoryException.Field.SOURCE);
    }
}
