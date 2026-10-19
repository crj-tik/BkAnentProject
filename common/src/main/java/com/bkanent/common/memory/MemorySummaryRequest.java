package com.bkanent.common.memory;

import java.io.Serializable;

/** Submits a compression candidate with its covered range and revision expectations; adoption stays server-side. */
public record MemorySummaryRequest(MemoryRequestEnvelope envelope, MemorySummary summary) implements Serializable, MemoryV2Request {
    private static final long serialVersionUID = 1L;

    public MemorySummaryRequest {
        MemoryV2Contract.requireOperation(envelope, MemoryV2Contract.OPERATION_SUBMIT_SUMMARY);
        MemoryContract.required(summary, MemoryException.Field.SUMMARY);
    }

    @Override
    public MemoryContextKey contextKey() { return summary.contextKey(); }
}
