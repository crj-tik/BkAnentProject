package com.bkanent.listing.source;

import com.bkanent.common.listing.ListingDataMode;
import com.bkanent.common.listing.ListingMoney;
import com.bkanent.common.listing.ListingRef;
import com.bkanent.common.listing.ListingSnapshotFacts;
import com.bkanent.common.listing.ListingSourceSnapshot;

import java.math.BigDecimal;
import java.time.Instant;

final class ListingSourceSnapshotFactory {
    private ListingSourceSnapshotFactory() { }

    static ListingSourceSnapshot create(ListingRef ref, String revision, Instant observedAt,
                                        ListingDataMode mode, String datasetId, ListingSnapshotFacts facts) {
        return com.bkanent.common.listing.ListingSnapshotFactory.create(ref, revision, observedAt,
                mode, datasetId, facts);
    }

    static String contentHash(ListingRef ref, ListingDataMode mode, String datasetId, ListingSnapshotFacts facts) {
        return com.bkanent.common.listing.ListingSnapshotFactory.contentHash(ref, mode, datasetId, facts);
    }

    static String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    static ListingMoney yuan(BigDecimal amount) {
        return amount == null ? null : new ListingMoney(decimal(amount), "CNY", ListingMoney.Unit.YUAN);
    }
}
