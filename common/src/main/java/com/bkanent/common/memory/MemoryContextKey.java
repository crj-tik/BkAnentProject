package com.bkanent.common.memory;

import java.io.Serializable;

/** Four independent logical identity segments, not an authorization grant or a transport address. */
public record MemoryContextKey(String ownerId, String domainId, String agentId, String conversationId)
        implements Serializable {
    private static final long serialVersionUID = 1L;

    public MemoryContextKey {
        ownerId = MemoryContract.id(ownerId, MemoryException.Field.IDENTITY);
        domainId = MemoryContract.id(domainId, MemoryException.Field.IDENTITY);
        agentId = MemoryContract.id(agentId, MemoryException.Field.IDENTITY);
        conversationId = MemoryContract.id(conversationId, MemoryException.Field.IDENTITY);
    }
}
