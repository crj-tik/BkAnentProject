package com.bkanent.common.memory;

import java.io.Serializable;
import java.util.List;
import java.util.Set;

/** Safe status for a committed or pending memory export; references never authorize a read. */
public record MemoryExportReceipt(List<MemoryExportRef> memoryRefs, String exportState,
        MemoryRevision version, MemoryIndexState indexState) implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final Set<String> STATES = Set.of(
            "PENDING", "COMMITTED", "FAILED", "WITHDRAWN", "DELETION_PENDING", "DELETED", "UNAVAILABLE", "NOT_RECORDED");

    public MemoryExportReceipt {
        memoryRefs = MemoryContract.list(memoryRefs, MemoryException.Field.EXPORT);
        exportState = MemoryContract.id(exportState, MemoryException.Field.STATE);
        MemoryContract.require(STATES.contains(exportState), MemoryException.Field.STATE);
        MemoryContract.require("COMMITTED".equals(exportState) == (memoryRefs.size() == 1), MemoryException.Field.EXPORT);
        MemoryContract.require(memoryRefs.size() <= 1, MemoryException.Field.EXPORT);
        if (!memoryRefs.isEmpty()) {
            MemoryContract.required(version, MemoryException.Field.VERSION);
            MemoryContract.require(memoryRefs.get(0).version().equals(version), MemoryException.Field.VERSION);
            MemoryContract.required(indexState, MemoryException.Field.STATE);
        }
        MemoryContract.require(memoryRefs.isEmpty() || "COMMITTED".equals(exportState), MemoryException.Field.EXPORT);
    }

    public static MemoryExportReceipt from(MemorySharedExportStatusResponse response) {
        return new MemoryExportReceipt(response.committedRef() == null ? List.of() : List.of(response.committedRef()),
                response.state().name(), response.version(), response.indexState());
    }

    public static MemoryExportReceipt failure(String state) {
        return new MemoryExportReceipt(List.of(), state, null, null);
    }
}
