package com.bkanent.common.memory;

import java.io.Serializable;
import java.util.List;

/** Grant plus the committed shared exports issued under it; current authorization is rechecked per entry. */
public record MemoryGrantResponse(MemoryShareGrant grant, List<MemorySharedExport> exports) implements Serializable {
    private static final long serialVersionUID = 1L;

    public MemoryGrantResponse {
        MemoryContract.required(grant, MemoryException.Field.GRANT);
        exports = MemoryContract.list(exports, MemoryException.Field.EXPORT);
    }
}
