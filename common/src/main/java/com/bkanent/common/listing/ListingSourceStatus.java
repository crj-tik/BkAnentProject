package com.bkanent.common.listing;

/** Result of checking whether one exact source revision can still be used. */
public enum ListingSourceStatus {
    CURRENT,
    STALE,
    NOT_ACCESSIBLE,
    UNAVAILABLE
}
