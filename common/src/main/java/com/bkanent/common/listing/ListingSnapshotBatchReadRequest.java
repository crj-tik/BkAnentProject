package com.bkanent.common.listing;

import java.io.Serializable;
import java.util.List;

public record ListingSnapshotBatchReadRequest(
        String contractVersion,
        List<ListingSourceVersionRef> refs) implements Serializable {
    public ListingSnapshotBatchReadRequest {
        refs = refs == null ? null : List.copyOf(refs);
    }
}
