package com.bkanent.common.memory;

import java.io.Serializable;
import java.util.List;

/** Delivers accepted events under a previously issued permit; the permit cannot grant reads or searches. */
public record MemoryDeliveryRequest(MemoryRequestEnvelope envelope, MemoryContextKey contextKey,
                                     MemoryAssociation association, String permitId,
                                     List<MemoryMessage> messages) implements Serializable {
    private static final long serialVersionUID = 1L;

    public MemoryDeliveryRequest {
        MemoryV2Contract.requireOperation(envelope, MemoryV2Contract.OPERATION_DELIVER_MESSAGES);
        MemoryContract.required(contextKey, MemoryException.Field.IDENTITY);
        MemoryContract.required(association, MemoryException.Field.ASSOCIATION);
        permitId = MemoryContract.id(permitId, MemoryException.Field.GRANT);
        messages = MemoryContract.nonempty(messages, MemoryException.Field.ROLE);
    }
}
