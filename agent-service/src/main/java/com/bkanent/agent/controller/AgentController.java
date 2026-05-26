package com.bkanent.agent.controller;

import com.bkanent.agent.mcp.AgentMcpClient;
import com.bkanent.agent.mcp.model.AgentMcpToolDescriptor;
import com.bkanent.agent.mcp.model.AgentToolCatalogItem;
import com.bkanent.agent.mcp.model.DiscoveredMcpTool;
import com.bkanent.agent.mcp.model.McpServerStatus;
import com.bkanent.agent.mcp.model.RegisteredMcpTool;
import com.bkanent.agent.model.chat.AgentChatRequest;
import com.bkanent.agent.model.chat.AgentChatResponse;
import com.bkanent.agent.model.distributed.SupervisorAsyncTaskCreateResponse;
import com.bkanent.agent.model.distributed.SupervisorAsyncTaskStatusResponse;
import com.bkanent.agent.model.distributed.SupervisorAsyncWorkflowCreateResponse;
import com.bkanent.agent.model.distributed.SupervisorAsyncWorkflowStatusResponse;
import com.bkanent.agent.model.distributed.AgentCatalogView;
import com.bkanent.agent.model.distributed.GovernanceGrayReleaseOverrideRequest;
import com.bkanent.agent.model.distributed.GovernanceRateLimitOverrideRequest;
import com.bkanent.agent.model.distributed.SessionEventAuditView;
import com.bkanent.agent.model.distributed.SupervisorGovernanceView;
import com.bkanent.agent.model.distributed.SupervisorDiagnosticsView;
import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.agent.model.distributed.SupervisorTaskResponse;
import com.bkanent.agent.model.distributed.SupervisorWorkflowView;
import com.bkanent.agent.model.distributed.TaskArtifactView;
import com.bkanent.agent.model.rag.ListingIndexRequest;
import com.bkanent.agent.model.rag.ListingRagQueryRequest;
import com.bkanent.agent.model.rag.ListingRagResponse;
import com.bkanent.agent.milvus.core.model.MilvusCollectionInitRequest;
import com.bkanent.agent.milvus.core.model.MilvusSearchResult;
import com.bkanent.agent.milvus.listing.ListingMilvusService;
import com.bkanent.agent.milvus.memory.AgentMemoryMilvusService;
import com.bkanent.agent.service.AgentCapabilityService;
import com.bkanent.agent.service.AgentOrchestratorService;
import com.bkanent.agent.service.AgentPermissionService;
import com.bkanent.agent.service.SupervisorGovernanceService;
import com.bkanent.agent.service.SupervisorAsyncTaskService;
import com.bkanent.agent.service.SupervisorAsyncWorkflowService;
import com.bkanent.agent.service.SupervisorAgentCatalogService;
import com.bkanent.agent.service.SupervisorDiagnosticsService;
import com.bkanent.agent.service.SupervisorEventAuditQueryService;
import com.bkanent.agent.service.SupervisorTaskService;
import com.bkanent.agent.service.SupervisorWorkflowQueryService;
import com.bkanent.agent.service.SupervisorWorkflowService;
import com.bkanent.agent.service.AgentToolCatalogService;
import com.bkanent.agent.service.McpDebugService;
import com.bkanent.agent.stream.SessionStreamService;
import com.bkanent.common.agent.AgentCard;
import com.bkanent.common.agent.ApprovalCallbackRequest;
import com.bkanent.common.agent.GovernanceErrorCodes;
import com.bkanent.common.agent.PermissionErrorCodes;
import com.bkanent.common.model.ApiResponse;
import com.bkanent.common.model.CompareReportDTO;
import com.bkanent.common.model.HealthStatusDTO;
import com.bkanent.common.model.MarketingContentDTO;
import com.bkanent.common.model.PublishRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * AgentController 控制器。
 */
@RestController
@RequestMapping("/agent")
public class AgentController {

    /**
     * 字段：agentMemoryMilvusService。
     */
    private final AgentMemoryMilvusService agentMemoryMilvusService;
    /**
     * 字段：listingMilvusService。
     */
    private final ListingMilvusService listingMilvusService;
    /**
     * 字段：agentOrchestratorService。
     */
    private final AgentOrchestratorService agentOrchestratorService;
    /**
     * 字段：agentCapabilityService。
     */
    private final AgentCapabilityService agentCapabilityService;
    /**
     * 字段：agentMcpClient。
     */
    private final AgentMcpClient agentMcpClient;
    /**
     * 字段：agentToolCatalogService。
     */
    private final AgentToolCatalogService agentToolCatalogService;
    /**
     * 字段：mcpDebugService。
     */
    private final McpDebugService mcpDebugService;
    /**
     * 字段：supervisorTaskService。
     */
    private final SupervisorTaskService supervisorTaskService;
    private final SupervisorAsyncTaskService supervisorAsyncTaskService;
    private final SupervisorAsyncWorkflowService supervisorAsyncWorkflowService;
    /**
     * 字段：supervisorWorkflowService。
     */
    private final SupervisorWorkflowService supervisorWorkflowService;
    private final SupervisorWorkflowQueryService supervisorWorkflowQueryService;
    private final SupervisorDiagnosticsService supervisorDiagnosticsService;
    private final SupervisorAgentCatalogService supervisorAgentCatalogService;
    private final SupervisorEventAuditQueryService supervisorEventAuditQueryService;
    private final SessionStreamService sessionStreamService;
    private final AgentPermissionService agentPermissionService;
    private final SupervisorGovernanceService supervisorGovernanceService;

    /**
     * 构造 AgentController 实例。
     */
    public AgentController(AgentMemoryMilvusService agentMemoryMilvusService,
                           ListingMilvusService listingMilvusService,
                           AgentOrchestratorService agentOrchestratorService,
                           AgentCapabilityService agentCapabilityService,
                           AgentMcpClient agentMcpClient,
                           AgentToolCatalogService agentToolCatalogService,
                           McpDebugService mcpDebugService,
                           SupervisorTaskService supervisorTaskService,
                           SupervisorAsyncTaskService supervisorAsyncTaskService,
                           SupervisorAsyncWorkflowService supervisorAsyncWorkflowService,
                           SupervisorWorkflowService supervisorWorkflowService,
                           SupervisorWorkflowQueryService supervisorWorkflowQueryService,
                           SupervisorDiagnosticsService supervisorDiagnosticsService,
                           SupervisorAgentCatalogService supervisorAgentCatalogService,
                           SupervisorEventAuditQueryService supervisorEventAuditQueryService,
                           SessionStreamService sessionStreamService,
                           AgentPermissionService agentPermissionService,
                           SupervisorGovernanceService supervisorGovernanceService) {
        this.agentMemoryMilvusService = agentMemoryMilvusService;
        this.listingMilvusService = listingMilvusService;
        this.agentOrchestratorService = agentOrchestratorService;
        this.agentCapabilityService = agentCapabilityService;
        this.agentMcpClient = agentMcpClient;
        this.agentToolCatalogService = agentToolCatalogService;
        this.mcpDebugService = mcpDebugService;
        this.supervisorTaskService = supervisorTaskService;
        this.supervisorAsyncTaskService = supervisorAsyncTaskService;
        this.supervisorAsyncWorkflowService = supervisorAsyncWorkflowService;
        this.supervisorWorkflowService = supervisorWorkflowService;
        this.supervisorWorkflowQueryService = supervisorWorkflowQueryService;
        this.supervisorDiagnosticsService = supervisorDiagnosticsService;
        this.supervisorAgentCatalogService = supervisorAgentCatalogService;
        this.supervisorEventAuditQueryService = supervisorEventAuditQueryService;
        this.sessionStreamService = sessionStreamService;
        this.agentPermissionService = agentPermissionService;
        this.supervisorGovernanceService = supervisorGovernanceService;
    }

    /**
     * 发布preview。
     */
    @PostMapping("/publish-preview")
    public ApiResponse<List<MarketingContentDTO>> publishPreview(@RequestBody PublishRequest request) {
        return ApiResponse.ok(agentCapabilityService.publishListingByPrompt(request.prompt()));
    }

    /**
     * 对比preview。
     */
    @GetMapping("/compare-preview")
    public ApiResponse<CompareReportDTO> comparePreview() {
        return ApiResponse.ok(agentCapabilityService.analyzeListings(List.of(1L, 2L)));
    }

    /**
     * 处理milvusSearch。
     */
    @GetMapping({"/vector-store/milvus/search", "/mcp/milvus/search"})
    public ApiResponse<List<MilvusSearchResult>> milvusSearch(@RequestParam String userId,
                                                              @RequestParam String query) {
        return guarded(() -> {
            agentPermissionService.assertPermission(userId, "agent.rag.memory.search", "memory rag search");
            return ApiResponse.ok(agentMemoryMilvusService.searchKnowledge(null, query, 5));
        });
    }

    /**
     * 查询mcpTools。
     */
    @GetMapping("/mcp/tools")
    public ApiResponse<List<AgentMcpToolDescriptor>> listMcpTools(@RequestParam String userId) {
        return guarded(() -> {
            agentPermissionService.assertPermission(userId, "agent.mcp.tools.read", "mcp tool listing");
            return ApiResponse.ok(agentMcpClient.listTools().stream()
                    .filter(tool -> agentPermissionService.canReadMcpServer(userId, tool.serverName()))
                    .toList());
        });
    }

    /**
     * 查询mcpToolCatalog。
     */
    @GetMapping("/mcp/tools/catalog")
    public ApiResponse<List<AgentToolCatalogItem>> listMcpToolCatalog(@RequestParam String userId) {
        return guarded(() -> {
            agentPermissionService.assertPermission(userId, "agent.mcp.catalog.read", "mcp tool catalog");
            return ApiResponse.ok(agentToolCatalogService.listCatalog().stream()
                    .filter(item -> agentPermissionService.canReadMcpServer(userId, item.serverName()))
                    .toList());
        });
    }

    /**
     * 查询discoveredMcpTools。
     */
    @GetMapping("/mcp/tools/discovered")
    public ApiResponse<List<DiscoveredMcpTool>> listDiscoveredMcpTools(@RequestParam String userId) {
        return guarded(() -> {
            agentPermissionService.assertPermission(userId, "agent.mcp.debug.read", "mcp discovered tool listing");
            return ApiResponse.ok(mcpDebugService.listDiscoveredTools().stream()
                    .filter(item -> agentPermissionService.canReadMcpServer(userId, item.serverName()))
                    .toList());
        });
    }

    /**
     * 查询registeredMcpTools。
     */
    @GetMapping("/mcp/tools/registered")
    public ApiResponse<List<RegisteredMcpTool>> listRegisteredMcpTools(@RequestParam String userId) {
        return guarded(() -> {
            agentPermissionService.assertPermission(userId, "agent.mcp.debug.read", "mcp registered tool listing");
            return ApiResponse.ok(mcpDebugService.listRegisteredTools().stream()
                    .filter(item -> agentPermissionService.canReadMcpServer(userId, item.serverName()))
                    .toList());
        });
    }

    /**
     * 查询mcpServerStatuses。
     */
    @GetMapping("/mcp/servers/status")
    public ApiResponse<List<McpServerStatus>> listMcpServerStatuses(@RequestParam String userId) {
        return guarded(() -> {
            agentPermissionService.assertPermission(userId, "agent.mcp.debug.read", "mcp server status listing");
            return ApiResponse.ok(mcpDebugService.listServerStatuses().stream()
                    .filter(item -> agentPermissionService.canReadMcpServer(userId, item.serverName()))
                    .toList());
        });
    }

    /**
     * 初始化collection。
     */
    @PostMapping({"/vector-store/milvus/collections/init", "/mcp/milvus/collections/init"})
    public ApiResponse<Void> initCollection(@RequestParam String userId,
                                            @RequestBody MilvusCollectionInitRequest request) {
        return guarded(() -> {
            agentPermissionService.assertPermission(userId, "agent.rag.memory.init", "memory collection init");
            agentMemoryMilvusService.initializeMemoryCollection(request.collectionName());
            return ApiResponse.ok(null);
        });
    }

    /**
     * 索引listing。
     */
    @PostMapping("/rag/listings/index")
    public ApiResponse<Void> indexListing(@RequestBody ListingIndexRequest request) {
        return guarded(() -> {
            agentPermissionService.assertPermission(request.userId(), "agent.rag.listing.index", "listing rag indexing");
            listingMilvusService.indexListing(request);
            return ApiResponse.ok(null);
        });
    }

    /**
     * 查询ingRag。
     */
    @PostMapping("/rag/listings/query")
    public ApiResponse<ListingRagResponse> listingRag(@RequestBody ListingRagQueryRequest request) {
        return guarded(() -> {
            agentPermissionService.assertPermission(request.userId(), "agent.rag.listing.query", "listing rag query");
            return ApiResponse.ok(listingMilvusService.query(request));
        });
    }

    /**
     * 处理对话。
     */
    @PostMapping("/chat")
    public ApiResponse<AgentChatResponse> chat(@RequestBody AgentChatRequest request) {
        return guarded(() -> {
            agentPermissionService.assertPermission(request.userId(), "agent.chat.use", "agent chat");
            if (Boolean.TRUE.equals(request.allowMcp())) {
                agentPermissionService.assertPermission(request.userId(), "agent.mcp.chat.use", "agent chat with mcp");
            }
            return ApiResponse.ok(agentOrchestratorService.chat(request));
        });
    }

    /**
     * 处理 supervisorTask。
     */
    @PostMapping("/supervisor/tasks")
    public ApiResponse<SupervisorTaskResponse> submitSupervisorTask(@RequestBody SupervisorTaskRequest request) {
        return guarded(() -> {
            supervisorGovernanceService.assertRateLimit("supervisor.tasks", request);
            return ApiResponse.ok(supervisorTaskService.submitTask(supervisorGovernanceService.applyGrayContext(request)));
        });
    }

    @PostMapping("/supervisor/tasks/async")
    public ApiResponse<SupervisorAsyncTaskCreateResponse> submitSupervisorTaskAsync(@RequestBody SupervisorTaskRequest request) {
        return guarded(() -> {
            supervisorGovernanceService.assertRateLimit("supervisor.tasks.async", request);
            return ApiResponse.ok(supervisorAsyncTaskService.submitTask(supervisorGovernanceService.applyGrayContext(request)));
        });
    }

    @GetMapping("/supervisor/tasks/async/status")
    public ApiResponse<SupervisorAsyncTaskStatusResponse> querySupervisorAsyncTaskStatus(@RequestParam String asyncTaskId,
                                                                                         @RequestParam String userId) {
        SupervisorAsyncTaskStatusResponse response = supervisorAsyncTaskService.queryStatus(asyncTaskId, userId);
        if (response == null) {
            return ApiResponse.fail("SUPERVISOR_ASYNC_TASK_NOT_FOUND", "async task not found");
        }
        return ApiResponse.ok(response);
    }

    @GetMapping("/supervisor/tasks/async/stream")
    public SseEmitter streamSupervisorAsyncTask(@RequestParam String asyncTaskId,
                                                @RequestParam String userId) {
        SseEmitter emitter = new SseEmitter(0L);
        CompletableFuture.runAsync(() -> streamSupervisorAsyncTaskStatus(asyncTaskId, userId, emitter));
        return emitter;
    }

    /**
     * 处理 supervisorWorkflow。
     */
    @PostMapping("/supervisor/workflows")
    public ApiResponse<SupervisorTaskResponse> startSupervisorWorkflow(@RequestBody SupervisorTaskRequest request) {
        return guarded(() -> {
            supervisorGovernanceService.assertRateLimit("supervisor.workflows", request);
            return ApiResponse.ok(supervisorWorkflowService.startWorkflow(supervisorGovernanceService.applyGrayContext(request)));
        });
    }

    @PostMapping("/supervisor/workflows/async")
    public ApiResponse<SupervisorAsyncWorkflowCreateResponse> startSupervisorWorkflowAsync(@RequestBody SupervisorTaskRequest request) {
        return guarded(() -> {
            supervisorGovernanceService.assertRateLimit("supervisor.workflows.async", request);
            return ApiResponse.ok(supervisorAsyncWorkflowService.submitWorkflow(supervisorGovernanceService.applyGrayContext(request)));
        });
    }

    @GetMapping("/supervisor/workflows/async/status")
    public ApiResponse<SupervisorAsyncWorkflowStatusResponse> querySupervisorAsyncWorkflowStatus(@RequestParam String asyncWorkflowId,
                                                                                                 @RequestParam String userId) {
        SupervisorAsyncWorkflowStatusResponse response = supervisorAsyncWorkflowService.queryStatus(asyncWorkflowId, userId);
        if (response == null) {
            return ApiResponse.fail("SUPERVISOR_ASYNC_WORKFLOW_NOT_FOUND", "async workflow not found");
        }
        return ApiResponse.ok(response);
    }

    @GetMapping("/supervisor/workflows/async/stream")
    public SseEmitter streamSupervisorAsyncWorkflow(@RequestParam String asyncWorkflowId,
                                                    @RequestParam String userId) {
        return supervisorAsyncWorkflowService.subscribeWorkflowStream(asyncWorkflowId, userId);
    }

    @PostMapping("/supervisor/workflows/async/cancel")
    public ApiResponse<SupervisorAsyncWorkflowStatusResponse> cancelSupervisorAsyncWorkflow(@RequestParam String asyncWorkflowId,
                                                                                            @RequestParam String userId) {
        SupervisorAsyncWorkflowStatusResponse response = supervisorAsyncWorkflowService.cancelWorkflow(asyncWorkflowId, userId);
        if (response == null) {
            return ApiResponse.fail("SUPERVISOR_ASYNC_WORKFLOW_NOT_FOUND", "async workflow not found");
        }
        return ApiResponse.ok(response);
    }

    @PostMapping("/supervisor/workflows/async/retry")
    public ApiResponse<SupervisorAsyncWorkflowCreateResponse> retrySupervisorAsyncWorkflow(@RequestParam String asyncWorkflowId,
                                                                                           @RequestParam String userId) {
        try {
            SupervisorAsyncWorkflowCreateResponse response = supervisorAsyncWorkflowService.retryWorkflow(asyncWorkflowId, userId);
            if (response == null) {
                return ApiResponse.fail("SUPERVISOR_ASYNC_WORKFLOW_NOT_FOUND", "async workflow not found");
            }
            return ApiResponse.ok(response);
        } catch (IllegalStateException exception) {
            return ApiResponse.fail("SUPERVISOR_ASYNC_WORKFLOW_NOT_RETRYABLE", exception.getMessage());
        }
    }

    /**
     * 处理 approvalCallback。
     */
    @PostMapping("/supervisor/approvals/callback")
    public ApiResponse<SupervisorTaskResponse> handleApprovalCallback(@RequestBody ApprovalCallbackRequest request) {
        return ApiResponse.ok(supervisorWorkflowService.handleCallback(request));
    }

    @GetMapping("/supervisor/workflows/state")
    public ApiResponse<SupervisorWorkflowView> getWorkflowState(@RequestParam String taskId,
                                                                @RequestParam String userId) {
        return guarded(() -> ApiResponse.ok(supervisorWorkflowQueryService.findWorkflow(taskId, userId).orElse(null)));
    }

    @GetMapping("/supervisor/workflows/artifacts")
    public ApiResponse<List<TaskArtifactView>> listWorkflowArtifacts(@RequestParam String taskId,
                                                                     @RequestParam String userId) {
        try {
            return ApiResponse.ok(supervisorWorkflowQueryService.listArtifacts(taskId, userId));
        } catch (IllegalStateException exception) {
            return ApiResponse.fail(PermissionErrorCodes.ARTIFACT_ACCESS_DENIED, exception.getMessage());
        }
    }

    @GetMapping("/supervisor/diagnostics")
    public ApiResponse<SupervisorDiagnosticsView> diagnostics(@RequestParam String userId,
                                                              @RequestParam(required = false) String taskId,
                                                              @RequestParam(required = false) String traceId,
                                                              @RequestParam(required = false) String approvalId,
                                                              @RequestParam(required = false) String artifactId,
                                                              @RequestParam(required = false) String asyncTaskId,
                                                              @RequestParam(required = false) String asyncWorkflowId,
                                                              @RequestParam(required = false) String grayStrategyVersion) {
        return guarded(() -> ApiResponse.ok(supervisorDiagnosticsService.diagnose(
                userId, taskId, traceId, approvalId, artifactId, asyncTaskId, asyncWorkflowId, grayStrategyVersion
        )));
    }

    @GetMapping("/supervisor/agents/catalog")
    public ApiResponse<List<AgentCatalogView>> supervisorAgentCatalog() {
        return guarded(() -> ApiResponse.ok(supervisorAgentCatalogService.listCatalog()));
    }

    @GetMapping("/supervisor/event-audit")
    public ApiResponse<List<SessionEventAuditView>> eventAudit(@RequestParam(required = false) String taskId,
                                                               @RequestParam(required = false) String traceId,
                                                               @RequestParam(required = false) String approvalId,
                                                               @RequestParam(required = false) String artifactId,
                                                               @RequestParam(required = false) String asyncTaskId,
                                                               @RequestParam(required = false) String grayStrategyVersion,
                                                               @RequestParam(required = false) String asyncWorkflowId,
                                                               @RequestParam(required = false) Boolean includeArchived,
                                                               @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(supervisorEventAuditQueryService.query(
                taskId, traceId, approvalId, artifactId, asyncTaskId, grayStrategyVersion, asyncWorkflowId, includeArchived, limit
        ));
    }

    @GetMapping("/supervisor/governance")
    public ApiResponse<SupervisorGovernanceView> governance() {
        return ApiResponse.ok(supervisorGovernanceService.viewGovernance());
    }

    @PostMapping("/supervisor/governance/rate-limit")
    public ApiResponse<SupervisorGovernanceView> overrideRateLimit(@RequestBody GovernanceRateLimitOverrideRequest request) {
        return ApiResponse.ok(supervisorGovernanceService.overrideRateLimit(request));
    }

    @PostMapping("/supervisor/governance/rate-limit/clear")
    public ApiResponse<SupervisorGovernanceView> clearRateLimitOverride(@RequestParam(required = false) String entryType) {
        return ApiResponse.ok(supervisorGovernanceService.clearRateLimitOverride(entryType));
    }

    @PostMapping("/supervisor/governance/gray-release")
    public ApiResponse<SupervisorGovernanceView> overrideGrayRelease(@RequestBody GovernanceGrayReleaseOverrideRequest request) {
        return ApiResponse.ok(supervisorGovernanceService.overrideGrayRelease(request));
    }

    @PostMapping("/supervisor/governance/gray-release/clear")
    public ApiResponse<SupervisorGovernanceView> clearGrayReleaseOverride() {
        return ApiResponse.ok(supervisorGovernanceService.clearGrayReleaseOverride());
    }

    private <T> ApiResponse<T> guarded(java.util.function.Supplier<ApiResponse<T>> supplier) {
        try {
            return supplier.get();
        } catch (IllegalStateException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("rate limit exceeded")) {
                return ApiResponse.fail(GovernanceErrorCodes.RATE_LIMITED, exception.getMessage());
            }
            return ApiResponse.fail(PermissionErrorCodes.PERMISSION_DENIED, exception.getMessage());
        }
    }

    @GetMapping("/supervisor/stream")
    public SseEmitter subscribeSupervisorStream(@RequestParam String sessionId) {
        return sessionStreamService.subscribe(sessionId);
    }

    /**
     * 查询 supervisorAgents。
     */
    @GetMapping("/supervisor/agents")
    public ApiResponse<List<AgentCard>> listSupervisorAgents() {
        return ApiResponse.ok(supervisorTaskService.listAgents());
    }

    /**
     * 处理health。
     */
    @GetMapping("/health")
    public ApiResponse<HealthStatusDTO> health() {
        return ApiResponse.ok(new HealthStatusDTO("agent-service", "UP", "1.0.0-SNAPSHOT"));
    }

    private void streamSupervisorAsyncTaskStatus(String asyncTaskId, String userId, SseEmitter emitter) {
        try {
            while (true) {
                SupervisorAsyncTaskStatusResponse response = supervisorAsyncTaskService.queryStatus(asyncTaskId, userId);
                if (response == null) {
                    emitter.send(SseEmitter.event()
                            .name("supervisor-async-status")
                            .data(ApiResponse.fail("SUPERVISOR_ASYNC_TASK_NOT_FOUND", "async task not found")));
                    emitter.complete();
                    return;
                }
                emitter.send(SseEmitter.event().name("supervisor-async-status").data(response));
                if (isTerminalStatus(response.status())) {
                    emitter.complete();
                    return;
                }
                Thread.sleep(1000L);
            }
        } catch (IOException exception) {
            emitter.completeWithError(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            emitter.completeWithError(exception);
        }
    }


    private boolean isTerminalStatus(String status) {
        return "COMPLETED".equalsIgnoreCase(status)
                || "FAILED".equalsIgnoreCase(status)
                || "CANCELLED".equalsIgnoreCase(status)
                || "completed".equalsIgnoreCase(status)
                || "failed".equalsIgnoreCase(status)
                || "cancelled".equalsIgnoreCase(status);
    }
}
