package com.bkanent.listing.service.impl;

import com.bkanent.common.model.ListingKeywordSearchRequest;
import com.bkanent.common.model.ListingKeywordSearchResultDTO;
import com.bkanent.common.rag.RagListingSearchRequest;
import com.bkanent.listing.rag.ListingRagCredentialContext;
import com.bkanent.listing.rag.ListingRagFacade;
import com.bkanent.listing.service.ListingSearchService;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.UUID;

/** The legacy tool name now delegates to authorized hybrid + rerank, never local fallback. */
@Service
public class ListingSearchServiceImpl implements ListingSearchService {
    private final ListingRagFacade facade;
    public ListingSearchServiceImpl(ListingRagFacade facade) { this.facade = facade; }
    @Override
    public List<ListingKeywordSearchResultDTO> searchByKeyword(ListingKeywordSearchRequest request) {
        return facade.search(new RagListingSearchRequest(1, UUID.randomUUID().toString(), request.keyword(), request.region(),
                request.layout(), request.minArea(), request.maxArea(), request.minTotalPrice(), request.maxTotalPrice(),
                Math.max(request.topK() == null ? 5 : request.topK(), 100),
                Math.max(request.topK() == null ? 5 : request.topK(), 100), request.topK()), ListingRagCredentialContext.current(), null).matches().stream()
                .map(match -> new ListingKeywordSearchResultDTO(match.listing(), match.relevanceScore())).toList();
    }
}
