package com.bkanent.agent.milvus.listing;

import com.bkanent.agent.config.AgentRagConsumerProperties;
import com.bkanent.agent.model.rag.*;
import com.bkanent.agent.rag.AgentRagCompatibility;
import com.bkanent.agent.security.AgentRagCredentialLeaseStore;
import com.bkanent.agent.security.AgentRagRequestAuthentication;
import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.RagListingDomainRpcService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.util.UUID;

/** Old API shape; all search/index authority and latest-source checks remain in listing domain. */
@Service
public class ListingMilvusService {
    private final ObjectProvider<RagListingDomainRpcService> domain;
    private final AgentRagConsumerProperties properties;
    private final AgentRagCredentialLeaseStore credentials;
    private final AgentRagRequestAuthentication authentication;
    public ListingMilvusService(ObjectProvider<RagListingDomainRpcService> domain, AgentRagConsumerProperties properties,
                                AgentRagCredentialLeaseStore credentials, AgentRagRequestAuthentication authentication) {
        this.domain = domain; this.properties = properties; this.credentials = credentials; this.authentication = authentication;
    }
    public void indexListing(ListingIndexRequest legacy) {
        requireListingSpace(legacy.collectionName());
        var request = new RagListingIndexRequest(1, UUID.randomUUID().toString(), legacy.listingId());
        var token = credentials.credentials(legacy.userId());
        try {
            var status = provider().index(request, authentication.user(request, request.requestId(), "listing-master-service",
                    RagOperation.SUBMIT, legacy.userId(), token), token);
            if (status == null) throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
            if (status.state() == RagIndexState.CONFLICT) throw new RagException(RagErrorCode.INDEX_CONFLICT);
            if (status.state() == RagIndexState.STALE) throw new RagException(RagErrorCode.INDEX_CONFLICT);
            if (status.state() == null) throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        } catch (RagException exception) { throw exception; }
        catch (RuntimeException exception) { throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE); }
    }
    public ListingRagResponse query(ListingRagQueryRequest legacy) {
        requireListingSpace(legacy.collectionName());
        int top = AgentRagCompatibility.topK(legacy.topK(), properties.getFinalTopK());
        int keyword = legacy.keywordTopK() == null ? properties.getKeywordTopK() : legacy.keywordTopK();
        int vector = legacy.vectorTopK() == null ? properties.getVectorTopK() : legacy.vectorTopK();
        AgentRagCompatibility.listingCounts(keyword, vector, top);
        var request = new RagListingSearchRequest(1, UUID.randomUUID().toString(), legacy.query(), legacy.region(), legacy.layout(),
                legacy.minArea(), legacy.maxArea(), legacy.minTotalPrice(), legacy.maxTotalPrice(), keyword, vector, top);
        var token = credentials.credentials(legacy.userId());
        try {
            var response = provider().search(request, authentication.user(request, request.requestId(), "listing-master-service",
                    RagOperation.SEARCH, legacy.userId(), token), token);
            return new ListingRagResponse(legacy.query(), response.context(), response.matches().stream().map(match -> {
                var listing = match.listing();
                return new ListingRagMatch(listing.id(), listing.title(), listing.address(), listing.layout(), listing.area(),
                        listing.totalPrice(), match.sources().stream().map(Enum::name).toList(), match.keywordScore(),
                        match.vectorScore(), match.rerankScore(), match.candidate().content());
            }).toList(), response.retrieval());
        } catch (RagException exception) { throw exception; }
        catch (RuntimeException exception) { throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE); }
    }
    private void requireListingSpace(String alias) {
        if (!"listing.knowledge".equals(AgentRagCompatibility.space(alias, "listing.knowledge"))) throw new RagException(RagErrorCode.FORBIDDEN);
    }
    private RagListingDomainRpcService provider() {
        var provider = domain.getIfAvailable();
        if (provider == null) throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        return provider;
    }
}
