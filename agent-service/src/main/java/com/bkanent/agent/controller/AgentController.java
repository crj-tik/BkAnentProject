package com.bkanent.agent.controller;

import com.bkanent.agent.mcp.MilvusMcpTool;
import com.bkanent.agent.mcp.MilvusCollectionInitRequest;
import com.bkanent.agent.mcp.MilvusSearchResult;
import com.bkanent.agent.rag.ListingIndexRequest;
import com.bkanent.agent.rag.ListingMilvusRagService;
import com.bkanent.agent.rag.ListingRagQueryRequest;
import com.bkanent.agent.rag.ListingRagResponse;
import com.bkanent.agent.service.AgentChatRequest;
import com.bkanent.agent.service.AgentChatResponse;
import com.bkanent.agent.service.AgentOrchestratorService;
import com.bkanent.common.model.ApiResponse;
import com.bkanent.common.model.CompareReportDTO;
import com.bkanent.common.model.HealthStatusDTO;
import com.bkanent.common.model.MarketingContentDTO;
import com.bkanent.common.model.PublishRequest;
import com.bkanent.common.rpc.AgentRpcService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/agent")
public class AgentController {

    @DubboReference(check = false)
    private AgentRpcService agentRpcService;

    private final MilvusMcpTool milvusMcpTool;
    private final ListingMilvusRagService listingMilvusRagService;
    private final AgentOrchestratorService agentOrchestratorService;

    public AgentController(MilvusMcpTool milvusMcpTool,
                           ListingMilvusRagService listingMilvusRagService,
                           AgentOrchestratorService agentOrchestratorService) {
        this.milvusMcpTool = milvusMcpTool;
        this.listingMilvusRagService = listingMilvusRagService;
        this.agentOrchestratorService = agentOrchestratorService;
    }

    @PostMapping("/publish-preview")
    public ApiResponse<List<MarketingContentDTO>> publishPreview(@RequestBody PublishRequest request) {
        return ApiResponse.ok(agentRpcService.publishListingByPrompt(request.prompt()));
    }

    @GetMapping("/compare-preview")
    public ApiResponse<CompareReportDTO> comparePreview() {
        return ApiResponse.ok(agentRpcService.analyzeListings(List.of(1L, 2L)));
    }

    @GetMapping("/mcp/milvus/search")
    public ApiResponse<List<MilvusSearchResult>> milvusSearch(@RequestParam String query) {
        return ApiResponse.ok(milvusMcpTool.search(query, 5));
    }

    @PostMapping("/mcp/milvus/collections/init")
    public ApiResponse<Void> initCollection(@RequestBody MilvusCollectionInitRequest request) {
        milvusMcpTool.initializeCollection(request);
        return ApiResponse.ok(null);
    }

    @PostMapping("/rag/listings/index")
    public ApiResponse<Void> indexListing(@RequestBody ListingIndexRequest request) {
        listingMilvusRagService.indexListing(request);
        return ApiResponse.ok(null);
    }

    @PostMapping("/rag/listings/query")
    public ApiResponse<ListingRagResponse> listingRag(@RequestBody ListingRagQueryRequest request) {
        return ApiResponse.ok(listingMilvusRagService.query(request));
    }

    @PostMapping("/chat")
    public ApiResponse<AgentChatResponse> chat(@RequestBody AgentChatRequest request) {
        return ApiResponse.ok(agentOrchestratorService.chat(request));
    }

    @GetMapping("/health")
    public ApiResponse<HealthStatusDTO> health() {
        return ApiResponse.ok(new HealthStatusDTO("agent-service", "UP", "1.0.0-SNAPSHOT"));
    }
}
