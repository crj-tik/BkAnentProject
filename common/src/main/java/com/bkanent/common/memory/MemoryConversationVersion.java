package com.bkanent.common.memory;

import java.io.Serializable;

/** Zero revision/sequence permits an empty conversation; policy identity is explicit. */
public record MemoryConversationVersion(long revision, long lastSequence, String policyVersion) implements Serializable {
    private static final long serialVersionUID = 1L;
    public MemoryConversationVersion {
        MemoryContract.require(revision >= 0 && lastSequence >= 0, MemoryException.Field.VERSION);
        policyVersion = MemoryContract.id(policyVersion, MemoryException.Field.VERSION);
    }
}
