package com.bkanent.common.memory;

import java.io.Serializable;

/** Receipt for one idempotent governance decision. */
public record MemoryDomainExperienceManagementResponse(String aggregateId, long revision, State state,
        String contentHash) implements Serializable {
    private static final long serialVersionUID = 1L;
    public enum State { PUBLISHED, WITHDRAWN }

    public MemoryDomainExperienceManagementResponse {
        MemoryContract.require(aggregateId != null && aggregateId.matches("dge_[A-Za-z0-9._:%-]{1,120}"),
                MemoryException.Field.IDENTITY);
        MemoryContract.require(revision > 0, MemoryException.Field.VERSION);
        MemoryContract.required(state, MemoryException.Field.STATE);
        if (state == State.PUBLISHED) {
            MemoryContract.require(contentHash != null && contentHash.matches("[0-9a-f]{64}"), MemoryException.Field.HASH);
        } else {
            MemoryContract.require(contentHash == null, MemoryException.Field.HASH);
        }
    }
}
