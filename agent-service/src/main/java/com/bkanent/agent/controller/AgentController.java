package com.bkanent.agent.controller;

import com.bkanent.agent.mcp.AgentMcpClient;
import com.bkanent.agent.mcp.model.AgentMcpToolDescriptor;
import com.bkanent.agent.model.chat.AgentChatRequest;
import com.bkanent.agent.model.chat.AgentChatResponse;
import com.bkanent.agent.model.planner.AgentPlannerSessionLogResponse;
import com.bkanent.agent.model.rag.ListingIndexRequest;
import com.bkanent.agent.model.rag.ListingRagQueryRequest;
import com.bkanent.agent.model.rag.ListingRagResponse;
import com.bkanent.agent.model.vector.MilvusCollectionInitRequest;
import com.bkanent.agent.model.vector.MilvusSearchResult;
import com.bkanent.agent.rag.ListingMilvusRagService;
import com.bkanent.agent.service.AgentCapabilityService;
import com.bkanent.agent.service.AgentOrchestratorService;
import com.bkanent.agent.service.AgentPlannerQueryService;
import com.bkanent.agent.vector.MilvusVectorStoreTool;
import com.bkanent.common.model.ApiResponse;
import com.bkanent.common.model.CompareReportDTO;
import com.bkanent.common.model.HealthStatusDTO;
import com.bkanent.common.model.MarketingContentDTO;
import com.bkanent.common.model.PublishRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent HTTP controller.
 */
@RestController
@RequestMapping("/agent")
public class AgentController {

    private final MilvusVectorStoreTool milvusVectorStoreTool;
    private final ListingMilvusRagService listingMilvusRagService;
    private final AgentOrchestratorService agentOrchestratorService;
    private final AgentCapabilityService agentCapabilityService;
    private final AgentPlannerQueryService agentPlannerQueryService;
    private final AgentMcpClient agentMcpClient;

    public AgentController(MilvusVectorStoreTool milvusVectorStoreTool,
                           ListingMilvusRagService listingMilvusRagService,
                           AgentOrchestratorService agentOrchestratorService,
                           AgentCapabilityService agentCapabilityService,
                           AgentPlannerQueryService agentPlannerQueryService,
                           AgentMcpClient agentMcpClient) {
        this.milvusVectorStoreTool = milvusVectorStoreTool;
        this.listingMilvusRagService = listingMilvusRagService;
        this.agentOrchestratorService = agentOrchestratorService;
        this.agentCapabilityService = agentCapabilityService;
        this.agentPlannerQueryService = agentPlannerQueryService;
        this.agentMcpClient = agentMcpClient;
    }

    @PostMapping("/publish-preview")
    public ApiResponse<List<MarketingContentDTO>> publishPreview(@RequestBody PublishRequest request) {
        return ApiResponse.ok(agentCapabilityService.publishListingByPrompt(request.prompt()));
    }

    @GetMapping("/compare-preview")
    public ApiResponse<CompareReportDTO> comparePreview() {
        return ApiResponse.ok(agentCapabilityService.analyzeListings(List.of(1L, 2L)));
    }

    @GetMapping({"/vector-store/milvus/search", "/mcp/milvus/search"})
    public ApiResponse<List<MilvusSearchResult>> milvusSearch(@RequestParam String query) {
        return ApiResponse.ok(milvusVectorStoreTool.search(query, 5));
    }

    @GetMapping("/mcp/tools")
    public ApiResponse<List<AgentMcpToolDescriptor>> listMcpTools() {
        return ApiResponse.ok(agentMcpClient.listTools());
    }

    @PostMapping({"/vector-store/milvus/collections/init", "/mcp/milvus/collections/init"})
    public ApiResponse<Void> initCollection(@RequestBody MilvusCollectionInitRequest request) {
        milvusVectorStoreTool.initializeCollection(request);
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

    @GetMapping("/planner/sessions/{sessionNo}")
    public ApiResponse<AgentPlannerSessionLogResponse> getPlannerSession(@PathVariable String sessionNo) {
        return ApiResponse.ok(agentPlannerQueryService.getSessionLog(sessionNo));
    }

    @GetMapping("/health")
    public ApiResponse<HealthStatusDTO> health() {
        return ApiResponse.ok(new HealthStatusDTO("agent-service", "UP", "1.0.0-SNAPSHOT"));
    }
}
