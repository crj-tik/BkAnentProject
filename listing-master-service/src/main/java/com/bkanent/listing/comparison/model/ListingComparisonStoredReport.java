package com.bkanent.listing.comparison.model;

import java.time.Instant;

/** On-disk metadata; owner and immutable source fingerprint are mandatory for admission. */
public record ListingComparisonStoredReport(
        String ownerActorId,
        String cacheKey,
        String sourceVersionHash,
        ListingComparisonReportResponse report,
        Instant storedAt) { }
