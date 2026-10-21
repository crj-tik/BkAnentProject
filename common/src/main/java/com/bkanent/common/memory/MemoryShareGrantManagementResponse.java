package com.bkanent.common.memory;

import java.io.Serializable;

/** Sanitized current grant receipt; never a bearer permission. */
public record MemoryShareGrantManagementResponse(MemoryShareGrant grant, boolean changed) implements Serializable {
    private static final long serialVersionUID = 1L;
    public MemoryShareGrantManagementResponse {
        MemoryContract.required(grant, MemoryException.Field.GRANT);
    }
}
