package com.bkanent.listing.source;

import com.bkanent.common.listing.ListingBusinessSearchRequest;
import com.bkanent.common.listing.ListingSnapshotBatchReadItem;
import com.bkanent.common.listing.ListingSnapshotCheckItem;
import com.bkanent.common.listing.ListingSnapshotBatchReadRequest;
import com.bkanent.common.listing.ListingSnapshotCheckRequest;

import java.util.List;

/** One explicitly selected source; implementations must not fall back to another provider. */
public interface ListingSourceAdapter {
    ListingSourcePage search(ListingBusinessSearchRequest request, ListingAccessScope scope);

    List<ListingSnapshotBatchReadItem> batchRead(ListingSnapshotBatchReadRequest request, ListingAccessScope scope);

    List<ListingSnapshotCheckItem> check(ListingSnapshotCheckRequest request, ListingAccessScope scope);
}
