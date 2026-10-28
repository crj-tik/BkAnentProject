package com.bkanent.common.listing;

import java.io.Serializable;
import java.util.List;

public record ListingBusinessResponse<T>(
        String contractVersion,
        List<T> items,
        String nextCursor) implements Serializable {
    public static final String VERSION = "domain-parallel-v1";

    public ListingBusinessResponse {
        if (!VERSION.equals(contractVersion) || items == null) {
            throw new IllegalArgumentException("Invalid listing business response");
        }
        items = List.copyOf(items);
    }
}
