package com.bkanent.listing.tool;

import com.bkanent.common.model.ListingDTO;
import com.bkanent.common.model.ListingKeywordSearchRequest;
import com.bkanent.common.model.ListingKeywordSearchResultDTO;
import com.bkanent.listing.model.ListingDetailResponse;
import com.bkanent.listing.model.ListingUpsertRequest;
import com.bkanent.listing.service.ListingManagementService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ListingTools {

    private final ListingManagementService listingManagementService;

    public ListingTools(ListingManagementService listingManagementService) {
        this.listingManagementService = listingManagementService;
    }

    @Tool(description = "Get full detail of a property listing by its ID, including title, community, district, area, price, status, and attached assets.")
    public ListingDetailResponse getListingDetail(
            @ToolParam(description = "Listing ID") Long listingId) {
        return listingManagementService.getListingDetail(listingId);
    }

    @Tool(description = "Search property listings by keyword using ES BM25 full-text search. Returns scored candidates with listing summaries.")
    public List<ListingKeywordSearchResultDTO> searchListingsByKeyword(
            @ToolParam(description = "Search keyword, e.g. community name, district, or property features") String keyword,
            @ToolParam(description = "Maximum number of results to return, default 5") int topK) {
        return listingManagementService.searchListingSummariesByKeyword(
                new ListingKeywordSearchRequest(keyword, null, null, null, null, null, null,
                        Math.max(1, Math.min(topK <= 0 ? 5 : topK, 20))));
    }

    @Tool(description = "Search property listing summaries. Returns basic info (title, community, price) for matching listings.")
    public List<ListingDTO> searchListingSummaries(
            @ToolParam(description = "Search keyword") String keyword) {
        return listingManagementService.searchListingSummaries(keyword);
    }

    @Tool(description = "Get a listing summary by its ID. Returns title, community, district, total price, unit price, area, and status.")
    public ListingDTO getListingSummary(
            @ToolParam(description = "Listing ID") Long listingId) {
        return listingManagementService.getListingSummary(listingId);
    }
}
