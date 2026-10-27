package com.bkanent.common.listing;

import java.io.Serializable;
import java.util.List;

public record ListingSnapshotCheckRequest(
        String contractVersion,
        List<ListingSourceVersionRef> refs) implements Serializable {
    public ListingSnapshotCheckRequest {
        refs = refs == null ? null : List.copyOf(refs);
    }
}
