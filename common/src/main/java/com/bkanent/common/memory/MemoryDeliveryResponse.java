package com.bkanent.common.memory;

import java.io.Serializable;
import java.util.List;

/** Per-message durable acceptance under the permit; the resulting conversation version pins the tail. */
public record MemoryDeliveryResponse(String permitId, MemoryConversationVersion version,
                                      List<DeliveryResult> results) implements Serializable {
    private static final long serialVersionUID = 1L;

    public record DeliveryResult(long sequence, boolean accepted, String reason) implements Serializable {
        private static final long serialVersionUID = 1L;

        public DeliveryResult {
            MemoryContract.require(sequence > 0, MemoryException.Field.ASSOCIATION);
            reason = MemoryContract.optionalText(reason, MemoryException.Field.TEXT);
        }
    }

    public MemoryDeliveryResponse {
        permitId = MemoryContract.id(permitId, MemoryException.Field.GRANT);
        MemoryContract.required(version, MemoryException.Field.VERSION);
        results = MemoryContract.nonempty(results, MemoryException.Field.ROLE);
    }
}
