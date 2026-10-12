package com.bkanent.listing.rpc;

import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.RagListingDomainRpcService;
import com.bkanent.listing.config.ListingRagProperties;
import com.bkanent.listing.rag.*;
import org.apache.dubbo.config.annotation.DubboService;

@DubboService
public class RagListingDomainRpcServiceImpl implements RagListingDomainRpcService {
    private final ListingRagIdentity identity;
    private final ListingRagFacade facade;
    private final ListingRagBackfillService backfill;
    private final ListingRagDispatcher dispatcher;
    private final ListingRagProperties properties;
    public RagListingDomainRpcServiceImpl(ListingRagIdentity identity, ListingRagFacade facade,
                                          ListingRagBackfillService backfill, ListingRagDispatcher dispatcher, ListingRagProperties properties) {
        this.identity = identity; this.facade = facade; this.backfill = backfill; this.dispatcher = dispatcher; this.properties = properties;
    }
    @Override
    public RagListingSearchResponse search(RagListingSearchRequest request, RagAuthEnvelope authentication, RagCredentialContext credentials) {
        identity.verifyCaller(request, request.requestId(), RagOperation.SEARCH, authentication, credentials);
        return facade.search(request, credentials, authentication.actorId());
    }
    @Override
    public RagIndexStatus index(RagListingIndexRequest request, RagAuthEnvelope authentication, RagCredentialContext credentials) {
        identity.verifyCaller(request, request.requestId(), RagOperation.SUBMIT, authentication, credentials);
        if (authentication.actorMode() != RagActorMode.USER) { throw new RagException(RagErrorCode.FORBIDDEN); }
        AuthRagPrincipal principal = identity.requireAdmin(credentials);
        if (!String.valueOf(principal.userId()).equals(authentication.actorId())) { throw new RagException(RagErrorCode.AUTHENTICATION_FAILED); }
        RagIndexEvent event = backfill.index(request.listingId(), credentials);
        // Domain queued event is not RAG ACCEPTED: return only after persisted RAG confirmation, post-commit.
        return dispatcher.deliver(event.eventId());
    }
    @Override
    public RagIndexStatus reconcile(RagListingReconcileRequest request, RagAuthEnvelope authentication) {
        identity.verifyCaller(request, request.requestId(), RagOperation.SUBMIT, authentication, null);
        if (authentication.actorMode() != RagActorMode.SERVICE || !properties.isLegacyReconciliationEnabled()) {
            throw new RagException(RagErrorCode.LEGACY_RECONCILIATION_REQUIRED);
        }
        RagIndexEvent event = backfill.reconcile(request.listingId(), request.expectedRevision(), request.expectedContentHash(), request.deleteIntent());
        return dispatcher.deliver(event.eventId());
    }
}
