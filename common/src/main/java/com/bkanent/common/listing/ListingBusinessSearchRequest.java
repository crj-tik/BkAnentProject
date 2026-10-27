package com.bkanent.common.listing;

import java.io.Serializable;

public record ListingBusinessSearchRequest(
        String contractVersion,
        String keyword,
        String cursor,
        Integer limit) implements Serializable {
}
