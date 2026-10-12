package com.bkanent.listing.service;

import com.bkanent.common.model.ListingKeywordSearchRequest;
import com.bkanent.common.model.ListingKeywordSearchResultDTO;
import java.util.List;

/** Domain retrieval facade; indexing is exclusively transactional outbox. */
public interface ListingSearchService {
    List<ListingKeywordSearchResultDTO> searchByKeyword(ListingKeywordSearchRequest request);
}
