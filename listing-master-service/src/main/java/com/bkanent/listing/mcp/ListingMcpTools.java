package com.bkanent.listing.mcp;

import com.bkanent.common.a2a.VerifiedDomainAuthorization;
import com.bkanent.common.listing.ListingBusinessResponse;
import com.bkanent.common.listing.ListingBusinessSearchRequest;
import com.bkanent.common.listing.ListingComparisonCreateRequest;
import com.bkanent.common.listing.ListingSourceSnapshot;
import com.bkanent.common.listing.ListingSourceVersionRef;
import com.bkanent.common.model.ListingDTO;
import com.bkanent.common.tool.McpTool;
import com.bkanent.listing.comparison.ListingComparisonException;
import com.bkanent.listing.comparison.model.ListingComparisonReportResponse;
import com.bkanent.listing.model.ListingDetailResponse;
import com.bkanent.listing.comparison.ListingComparisonService;
import com.bkanent.listing.source.ListingBusinessSourceService;
import com.bkanent.listing.service.ListingManagementService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ListingMcpTools 房源域 MCP 只读工具集。
 * 与 A2A 的 ListingTools 分离（marketing/media 先例），调用同一服务层；
 * 每个工具经 {@link VerifiedDomainAuthorization#requireOperation} 在当前
 * MCP 授权上下文中校验精确操作码；不包含任何房源写操作
 * （登记、维护、状态、素材、业主、OCR、核验仅走 REST + 网关签名上下文）。
 */
@Component
public class ListingMcpTools implements McpTool {

    private final ListingManagementService listingManagementService;
    private final ListingBusinessSourceService businessSourceService;
    private final ListingComparisonService comparisonService;
    private final ObjectMapper objectMapper;

    public ListingMcpTools(ListingManagementService listingManagementService,
                           ListingBusinessSourceService businessSourceService,
                           ListingComparisonService comparisonService,
                           ObjectMapper objectMapper) {
        this.listingManagementService = listingManagementService;
        this.businessSourceService = businessSourceService;
        this.comparisonService = comparisonService;
        this.objectMapper = objectMapper;
    }

    @Tool(description = "Search property listing summaries within the current authorization scope. "
            + "Returns basic info (title, address, layout, area, price, status) for matching listings; "
            + "never returns owner contact details, certificate numbers, or restricted assets. "
            + "Public tool: gated by the server's public-tools whitelist, not a per-tool operation.")
    public List<ListingDTO> searchListingSummaries(
            @ToolParam(description = "Search keyword, e.g. community name or district") String keyword) {
        return listingManagementService.searchListingSummaries(keyword);
    }

    @Tool(description = "Get a listing summary by its ID. Returns title, address, layout, area, "
            + "total price (wan units), and status. "
            + "Public tool: gated by the server's public-tools whitelist, not a per-tool operation.")
    public ListingDTO getListingSummary(
            @ToolParam(description = "Listing ID") Long listingId) {
        return listingManagementService.getListingSummary(listingId);
    }

    @Tool(description = "Get full detail of a property listing by its ID, including title, community, "
            + "district, area, price, status, and attached assets. Requires listing.read; "
            + "owner-scoped records are checked against the current identity.")
    public ListingDetailResponse getListingDetail(
            @ToolParam(description = "Listing ID") Long listingId) {
        VerifiedDomainAuthorization.requireOperation("listing.read");
        return listingManagementService.getListingDetail(listingId);
    }

    @Tool(description = "Search the explicitly configured internal listing source and return stable "
            + "sourceSystem/sourceId references, exact revisions, content hashes, explicit CNY/YUAN units, "
            + "and server-confirmed dataMode. Use this result to prepare a version-bound comparison; "
            + "never invent or convert a reference or revision.")
    public ListingBusinessResponse<ListingSourceSnapshot> searchListingSource(
            @ToolParam(description = "Optional search keyword") String keyword,
            @ToolParam(description = "Page size from 1 to 100; default 20") Integer limit) {
        VerifiedDomainAuthorization.requireOperation("listing.read");
        return businessSourceService.search(new ListingBusinessSearchRequest(
                ListingBusinessResponse.VERSION, keyword, null, limit));
    }

    @Tool(description = "Create an owner-scoped Listing comparison from 2 to 6 stable source references "
            + "and exact revisions. refsJson must be a JSON array of objects shaped as "
            + "{\"listingRef\":{\"sourceSystem\":\"...\",\"sourceId\":\"...\"},\"expectedRevision\":\"...\"}. "
            + "Requires compare.report.create and current read access to every listing; the response "
            + "includes card projections (cards, metricWinners) and a deterministic conclusion. "
            + "Stale, inaccessible, or unavailable sources do not produce a report.")
    public ListingComparisonReportResponse createListingComparison(
            @ToolParam(description = "JSON array of ListingSourceVersionRef objects returned by searchListingSource") String refsJson,
            @ToolParam(description = "Whether to include a deterministic factual conclusion") boolean includeConclusion) {
        VerifiedDomainAuthorization.requireOperation("compare.report.create");
        try {
            List<ListingSourceVersionRef> refs = objectMapper.readValue(refsJson,
                    new TypeReference<List<ListingSourceVersionRef>>() { });
            return comparisonService.create(new ListingComparisonCreateRequest(
                    ListingBusinessResponse.VERSION, refs, includeConclusion));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw ListingComparisonException.invalidRequest();
        }
    }

    @Tool(description = "Read an existing Listing comparison report by its reportId, including card "
            + "projections and metric winners. Requires compare.report.read.own; the current user must "
            + "own the report and retain current read access to every source revision.")
    public ListingComparisonReportResponse getComparisonReport(
            @ToolParam(description = "reportId returned by createListingComparison") String reportId) {
        VerifiedDomainAuthorization.requireOperation("compare.report.read.own");
        return comparisonService.getOwned(reportId);
    }
}
