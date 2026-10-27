package com.bkanent.listing.tool;

import com.bkanent.common.model.ListingDTO;
import com.bkanent.common.model.ListingKeywordSearchRequest;
import com.bkanent.common.model.ListingKeywordSearchResultDTO;
import com.bkanent.common.listing.ListingBusinessResponse;
import com.bkanent.common.listing.ListingBusinessSearchRequest;
import com.bkanent.common.listing.ListingComparisonCreateRequest;
import com.bkanent.common.listing.ListingSourceSnapshot;
import com.bkanent.common.listing.ListingSourceVersionRef;
import com.bkanent.listing.comparison.ListingComparisonService;
import com.bkanent.listing.comparison.model.ListingComparisonReportResponse;
import com.bkanent.listing.source.ListingBusinessSourceService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ListingBusinessSourceService businessSourceService;
    private final ListingComparisonService comparisonService;
    private final ObjectMapper objectMapper;

    @Autowired
    public ListingTools(ListingManagementService listingManagementService,
                        ListingBusinessSourceService businessSourceService,
                        ListingComparisonService comparisonService,
                        ObjectMapper objectMapper) {
        this.listingManagementService = listingManagementService;
        this.businessSourceService = businessSourceService;
        this.comparisonService = comparisonService;
        this.objectMapper = objectMapper;
    }

    /** Kept for isolated callers that only exercise the pre-existing listing tools. */
    public ListingTools(ListingManagementService listingManagementService) {
        this.listingManagementService = listingManagementService;
        this.businessSourceService = null;
        this.comparisonService = null;
        this.objectMapper = null;
    }

    @Tool(description = "Get full detail of a property listing by its ID, including title, community, district, area, price, status, and attached assets.")
    public ListingDetailResponse getListingDetail(
            @ToolParam(description = "Listing ID") Long listingId) {
        return listingManagementService.getListingDetail(listingId);
    }

    @Tool(description = "Search authorized active property listings using default hybrid keyword/vector recall and semantic rerank in the isolated RAG service. Requires current authenticated request credentials; never accepts user IDs as authentication. Returns latest SQL-verified listing summaries and relevance scores, or an explicit authentication/dependency failure.")
    public List<ListingKeywordSearchResultDTO> searchListingsByKeyword(
            @ToolParam(description = "Search keyword, e.g. community name, district, or property features") String keyword,
            @ToolParam(description = "Maximum number of results to return, default 5") int topK) {
        return listingManagementService.searchListingSummariesByKeyword(
                new ListingKeywordSearchRequest(keyword, null, null, null, null, null, null,
                        topK == 0 ? 5 : topK));
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

    @Tool(description = "Search the explicitly configured listing source and return stable sourceSystem/sourceId references, exact revisions, content hashes, explicit CNY/YUAN units, and server-confirmed dataMode. Use this result to prepare a version-bound comparison; never invent or convert a reference or revision.")
    public ListingBusinessResponse<ListingSourceSnapshot> searchBusinessListings(
            @ToolParam(description = "Optional search keyword") String keyword,
            @ToolParam(description = "Page size from 1 to 100; default 20") Integer limit) {
        return businessSourceService.search(new ListingBusinessSearchRequest(
                ListingBusinessResponse.VERSION, keyword, null, limit));
    }

    @Tool(description = "Create an owner-scoped Listing comparison from 2 to 6 stable source references and exact revisions. refsJson must be a JSON array of objects shaped as {\"listingRef\":{\"sourceSystem\":\"...\",\"sourceId\":\"...\"},\"expectedRevision\":\"...\"}. Requires current compare creation and per-listing read permissions; stale, inaccessible, or unavailable sources do not produce a report.")
    public ListingComparisonReportResponse createListingComparison(
            @ToolParam(description = "JSON array of ListingSourceVersionRef objects returned by searchBusinessListings") String refsJson,
            @ToolParam(description = "Whether to include a deterministic factual conclusion") boolean includeConclusion) {
        try {
            List<ListingSourceVersionRef> refs = objectMapper.readValue(refsJson,
                    new TypeReference<List<ListingSourceVersionRef>>() { });
            return comparisonService.create(new ListingComparisonCreateRequest(
                    ListingBusinessResponse.VERSION, refs, includeConclusion));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw com.bkanent.listing.comparison.ListingComparisonException.invalidRequest();
        }
    }

    @Tool(description = "Read an existing Listing comparison report by its reportId. The current user must own the report, retain compare.report.read.own and current read access to every source revision.")
    public ListingComparisonReportResponse getListingComparisonReport(
            @ToolParam(description = "reportId returned by createListingComparison") String reportId) {
        return comparisonService.getOwned(reportId);
    }
}
