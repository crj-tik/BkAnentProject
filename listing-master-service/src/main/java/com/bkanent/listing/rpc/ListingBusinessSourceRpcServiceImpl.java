package com.bkanent.listing.rpc;

import com.bkanent.common.authz.AuthzResponse;
import com.bkanent.common.listing.ListingBusinessResponse;
import com.bkanent.common.listing.ListingSnapshotBatchReadItem;
import com.bkanent.common.listing.ListingSnapshotCheckItem;
import com.bkanent.common.listing.ListingSourceSnapshot;
import com.bkanent.common.rag.RagAuthEnvelope;
import com.bkanent.common.rag.RagAuthorizedListingBusinessBatchReadRequest;
import com.bkanent.common.rag.RagAuthorizedListingBusinessCheckRequest;
import com.bkanent.common.rag.RagAuthorizedListingBusinessSearchRequest;
import com.bkanent.common.rpc.ListingBusinessSourceRpcService;
import com.bkanent.listing.config.ListingAuthorizationProperties;
import com.bkanent.listing.source.ListingBusinessSourceService;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.function.Supplier;

/** Internal stable-source transport; every method opens the same verified user scope as the HTTP ingress. */
@DubboService
public class ListingBusinessSourceRpcServiceImpl implements ListingBusinessSourceRpcService {
    private final com.bkanent.listing.rag.ListingRagIdentity identity;
    private final ListingAuthorizationProperties authorizationProperties;
    private final ListingBusinessSourceService source;

    public ListingBusinessSourceRpcServiceImpl(com.bkanent.listing.rag.ListingRagIdentity identity,
                                               ListingAuthorizationProperties authorizationProperties,
                                               ListingBusinessSourceService source) {
        this.identity = identity;
        this.authorizationProperties = authorizationProperties;
        this.source = source;
    }

    @Override
    public ListingBusinessResponse<ListingSourceSnapshot> search(
            RagAuthorizedListingBusinessSearchRequest request, RagAuthEnvelope authentication,
            AuthzResponse authorization) {
        return authorized(request, request == null ? null : request.requestId(),
                request == null ? null : request.authorizationEvidenceHash(), authentication, authorization,
                () -> source.search(request.request()));
    }

    @Override
    public ListingBusinessResponse<ListingSnapshotBatchReadItem> batchRead(
            RagAuthorizedListingBusinessBatchReadRequest request, RagAuthEnvelope authentication,
            AuthzResponse authorization) {
        return authorized(request, request == null ? null : request.requestId(),
                request == null ? null : request.authorizationEvidenceHash(), authentication, authorization,
                () -> source.batchRead(request.request()));
    }

    @Override
    public ListingBusinessResponse<ListingSnapshotCheckItem> check(
            RagAuthorizedListingBusinessCheckRequest request, RagAuthEnvelope authentication,
            AuthzResponse authorization) {
        return authorized(request, request == null ? null : request.requestId(),
                request == null ? null : request.authorizationEvidenceHash(), authentication, authorization,
                () -> source.check(request.request()));
    }

    private <T> T authorized(Object request, String requestId, String authorizationEvidenceHash,
                             RagAuthEnvelope authentication, AuthzResponse authorization,
                             Supplier<T> action) {
        try (var ignored = identity.openAuthorizedBusinessSourceScope(request, requestId,
                authorizationEvidenceHash, authentication, authorization,
                authorizationProperties.decodedAuthorityPublicKeys())) {
            return action.get();
        }
    }
}
