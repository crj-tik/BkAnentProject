package com.bkanent.common.rpc;

import com.bkanent.common.authz.AuthzResponse;
import com.bkanent.common.listing.ListingBusinessResponse;
import com.bkanent.common.listing.ListingSnapshotBatchReadItem;
import com.bkanent.common.listing.ListingSnapshotCheckItem;
import com.bkanent.common.listing.ListingSourceSnapshot;
import com.bkanent.common.rag.RagAuthEnvelope;
import com.bkanent.common.rag.RagAuthorizedListingBusinessBatchReadRequest;
import com.bkanent.common.rag.RagAuthorizedListingBusinessCheckRequest;
import com.bkanent.common.rag.RagAuthorizedListingBusinessSearchRequest;

/** User-authorized internal transport for the stable Listing source API. */
public interface ListingBusinessSourceRpcService {
    ListingBusinessResponse<ListingSourceSnapshot> search(
            RagAuthorizedListingBusinessSearchRequest request, RagAuthEnvelope authentication, AuthzResponse authorization);
    ListingBusinessResponse<ListingSnapshotBatchReadItem> batchRead(
            RagAuthorizedListingBusinessBatchReadRequest request, RagAuthEnvelope authentication, AuthzResponse authorization);
    ListingBusinessResponse<ListingSnapshotCheckItem> check(
            RagAuthorizedListingBusinessCheckRequest request, RagAuthEnvelope authentication, AuthzResponse authorization);
}
