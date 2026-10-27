package com.bkanent.listing.source;

public class ListingSourceUnavailableException extends RuntimeException {
    public ListingSourceUnavailableException() {
        super("Selected listing source is unavailable");
    }
}
