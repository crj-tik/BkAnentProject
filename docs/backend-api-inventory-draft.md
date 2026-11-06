# 后端接口盘点（控制器级源码核对版）

**统一契约入口**：[前后端接口契约 v1](contracts/README.md)。本盘点用于定位 Controller 映射，不能替代该入口列出的域级请求/响应契约。

本文逐一核对所列控制器的请求映射。每行左侧是源码注解行号、映射注解和对应处理方法；类级路径与方法级路径需要组合，空参数的 `@GetMapping` / `@PostMapping` 表示类级路径本身。多路径别名按源码保留。该表用于定位接口，不展开 DTO 字段、错误响应、授权条件和运行配置，不能替代完整联调契约。若模块维护独立契约，应同时核对契约和实现；没有独立契约的接口，需查对应 DTO、异常处理和授权逻辑。

平台经营已维护 [OpenAPI 契约](contracts/platform-operations-v1.openapi.yaml) 和 [前端功能清单](platform-operations-frontend-function-list.md)。

网关当前路由：`/auth/**`、`/agent/**`、`/listings/**`、`/customers/**`、`/platform-operations/**`、`/marketing/**`、`/notifications/**`、`/interviews/**`；`/compare/**`、`/promotions/**` 为兼容路由。媒体、Memory、RAG 等服务接口需要结合实际调用方式判断，不应直接假定为浏览器入口。

本表范围内的 22 个控制器、226 个请求映射均已与源码逐项核对，补入此前遗漏的类级根路径和复盘诊断版本、Studio 命令回执等接口，并修正 Studio 控制器曾发生的注解/方法列错位。当前 Gateway 模板的 10 条活动路由均有精确受众映射，Auth `gateway-current` ACL 与去重后的受众一致；目标 Nacos 配置是否已发布及逐路由运行验收仍按 [KI-236](known-issues.md) 跟踪。路由存在不代表当前环境可直接联调。

## auth-service

### [RoleAuthorizationManagementController.java](../auth-service/src/main/java/com/bkanent/auth/authorization/RoleAuthorizationManagementController.java)
类级路径：`/auth/authorization`

| 行 | 映射注解 | 方法声明（字段定义见源码） |
| --- | --- | --- |
| 20 | `@GetMapping("/catalog")` | `public List<AuthorizationFacts.Permission> catalog(@RequestHeader(value="Authorization",required=false) String header)` |
| 25 | `@GetMapping("/roles")` | `public List<RoleAuthorizationManagementService.Role> roles(@RequestHeader(value="Authorization",required=false) String header)` |
| 30 | `@PostMapping("/roles")` | `public RoleAuthorizationManagementService.ManagementResult createRole(@RequestHeader(value="Authorization",required=false) String header, @RequestBody RoleAuthorizationManagementService.RoleDefinition body)` |
| 36 | `@PutMapping("/roles/{roleId}")` | `public RoleAuthorizationManagementService.ManagementResult updateRole(@RequestHeader(value="Authorization",required=false) String header, @PathVariable("roleId") long roleId, @RequestBody RoleAuthorizationManagementService.RoleDefinition body)` |
| 43 | `@DeleteMapping("/roles/{roleId}")` | `public RoleAuthorizationManagementService.ManagementResult deleteRole(@RequestHeader(value="Authorization",required=false) String header, @PathVariable("roleId") long roleId)` |
| 49 | `@GetMapping("/users/{userId}/roles")` | `public List<RoleAuthorizationManagementService.UserRoleGrant> grants(@RequestHeader(value="Authorization",required=false) String header, @PathVariable("userId") long userId)` |
| 55 | `@PutMapping("/users/{userId}/roles")` | `public RoleAuthorizationManagementService.ManagementResult replaceGrants(@RequestHeader(value="Authorization",required=false) String header, @PathVariable("userId") long userId,@RequestBody UserRoles body)` |
| 61 | `@PutMapping("/users/{userId}/business-admin")` | `public RoleAuthorizationManagementService.ManagementResult administrator(@RequestHeader(value="Authorization",required=false) String header, @PathVariable("userId") long userId,@RequestBody BusinessAdmin body)` |
| 67 | `@GetMapping("/users/{userId}/business-identities")` | `public List<RoleAuthorizationManagementService.BusinessIdentity> businessIdentities( @RequestHeader(value="Authorization",required=false) String header, @PathVariable("userId") long userId)` |
| 73 | `@PutMapping("/users/{userId}/broker-identity")` | `public RoleAuthorizationManagementService.BusinessIdentityChange brokerIdentity( @RequestHeader(value="Authorization",required=false) String header, @PathVariable("userId") long userId, @RequestBody BrokerIdentity body)` |
| 81 | `@PutMapping("/users/{userId}/store-identity")` | `public RoleAuthorizationManagementService.StoreIdentityChange storeIdentity( @RequestHeader(value="Authorization",required=false) String header, @PathVariable("userId") long userId, @RequestBody StoreIdentity body)` |

### [AuthController.java](../auth-service/src/main/java/com/bkanent/auth/controller/AuthController.java)
类级路径：`/auth`

| 行 | 映射注解 | 方法声明（字段定义见源码） |
| --- | --- | --- |
| 63 | `@PostMapping("/login")` | `public ApiResponse<AuthTokenDTO> login(@RequestBody AuthLoginRequest request)` |
| 95 | `@PostMapping("/refresh")` | `public ApiResponse<AuthTokenDTO> refresh(@RequestBody AuthRefreshRequest request)` |
| 131 | `@PostMapping("/logout")` | `public ApiResponse<Void> logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization)` |
| 143 | `@GetMapping("/me")` | `public ApiResponse<CurrentUser> currentUser( @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization)` |
| 150 | `@GetMapping("/roles")` | `public ApiResponse<List<RoleOption>> roles( @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization)` |
| 157 | `@GetMapping("/users")` | `public ApiResponse<PageResult<UserItem>> users( @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization, @RequestParam(name = "page", defaultValue = "1") long page, @RequestParam(name = "pageSize", defaultValue = "20") long pageSize, @RequestParam(name = "keyword", required = false) String keyword, @RequestParam(name = "roleCode", required = false) String roleCode, @RequestParam(name = "accountStatus", required = false) String accountStatus)` |
| 169 | `@PostMapping("/users")` | `public ResponseEntity<ApiResponse<UserItem>> createUser( @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization, @Valid @RequestBody CreateUserRequest request)` |
| 179 | `@PutMapping("/users/{userId}")` | `public ApiResponse<UserItem> updateUser( @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization, @PathVariable("userId") long userId, @Valid @RequestBody UpdateUserRequest request)` |
| 190 | `@PutMapping("/users/{userId}/password")` | `public ApiResponse<Void> resetPassword( @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization, @PathVariable("userId") long userId, @Valid @RequestBody ResetPasswordRequest request)` |
| 201 | `@DeleteMapping("/users/{userId}")` | `public ApiResponse<Void> deleteUser( @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization, @PathVariable("userId") long userId)` |
| 211 | `@GetMapping("/health")` | `public ApiResponse<HealthStatusDTO> health()` |

## agent-service

### [AgentController.java](../agent-service/src/main/java/com/bkanent/agent/controller/AgentController.java)
类级路径：`/agent`

| 行 | 映射注解 | 方法声明（字段定义见源码） |
| --- | --- | --- |
| 96 | `@PostMapping("/supervisor/runs/cancel")` | `public ApiResponse<SupervisorTaskResponse> cancelRun(@RequestParam String runId, @RequestParam(name = "userId", required = false) String userId)` |
| 106 | `@PostMapping("/supervisor/runs/reconcile")` | `public ApiResponse<SupervisorTaskResponse> reconcileRun(@RequestParam String runId, @RequestParam(name = "userId", required = false) String userId)` |
| 216 | `@PostMapping({"/marketing/drafts", "/publish-preview"})` | `public ApiResponse<List<MarketingContentDraftResponse>> createMarketingDraft(@RequestBody PublishRequest request)` |
| 224 | `@GetMapping({"/vector-store/milvus/search", "/mcp/milvus/search"})` | `public ApiResponse<List<MilvusSearchResult>> milvusSearch(@RequestParam(name = "userId") String userId, @RequestParam(name = "query") String query)` |
| 237 | `@GetMapping("/mcp/tools")` | `public ApiResponse<List<AgentMcpToolDescriptor>> listMcpTools(@RequestParam String userId)` |
| 252 | `@GetMapping("/mcp/tools/catalog")` | `public ApiResponse<List<AgentToolCatalogItem>> listMcpToolCatalog(@RequestParam String userId)` |
| 267 | `@GetMapping("/mcp/tools/discovered")` | `public ApiResponse<List<DiscoveredMcpTool>> listDiscoveredMcpTools(@RequestParam String userId)` |
| 282 | `@GetMapping("/mcp/tools/registered")` | `public ApiResponse<List<RegisteredMcpTool>> listRegisteredMcpTools(@RequestParam String userId)` |
| 297 | `@GetMapping("/mcp/servers/status")` | `public ApiResponse<List<McpServerStatus>> listMcpServerStatuses(@RequestParam String userId)` |
| 308 | `@GetMapping("/mcp/dynamic/connections")` | `public ApiResponse<List<String>> listDynamicMcpConnections(@RequestParam String userId)` |
| 317 | `@PostMapping("/mcp/dynamic/connect")` | `public ApiResponse<String> registerDynamicMcpConnection(@RequestParam String userId, @RequestBody DynamicMcpConnectionConfig config)` |
| 328 | `@DeleteMapping("/mcp/dynamic/connect/{name}")` | `public ApiResponse<String> unregisterDynamicMcpConnection(@RequestParam String userId, @PathVariable String name)` |
| 342 | `@PostMapping({"/vector-store/milvus/collections/init", "/mcp/milvus/collections/init"})` | `public ApiResponse<Void> initCollection(@RequestParam(name = "userId") String userId, @RequestBody MilvusCollectionInitRequest request)` |
| 356 | `@PostMapping("/rag/listings/index")` | `public ApiResponse<Void> indexListing(@RequestBody ListingIndexRequest request)` |
| 369 | `@PostMapping("/rag/listings/query")` | `public ApiResponse<ListingRagResponse> listingRag(@RequestBody ListingRagQueryRequest request)` |
| 381 | `@PostMapping("/chat")` | `public ApiResponse<AgentChatResponse> chat(@RequestBody AgentChatRequest request)` |
| 403 | `@PostMapping("/supervisor/tasks")` | `public ApiResponse<SupervisorTaskResponse> submitSupervisorTask(@RequestBody SupervisorTaskRequest request)` |
| 419 | `@PostMapping("/supervisor/tasks/async")` | `public ApiResponse<SupervisorAsyncTaskCreateResponse> submitSupervisorTaskAsync(@RequestBody SupervisorTaskRequest request)` |
| 435 | `@GetMapping("/supervisor/tasks/async/status")` | `public ApiResponse<SupervisorAsyncTaskStatusResponse> querySupervisorAsyncTaskStatus(@RequestParam String asyncTaskId, @RequestParam String userId)` |
| 446 | `@GetMapping("/supervisor/tasks/async/stream")` | `public SseEmitter streamSupervisorAsyncTask(@RequestParam String asyncTaskId, @RequestParam String userId)` |
| 471 | `@PostMapping("/supervisor/workflows")` | `public ApiResponse<SupervisorTaskResponse> startSupervisorWorkflow(@RequestBody SupervisorTaskRequest request)` |
| 487 | `@PostMapping("/supervisor/workflows/async")` | `public ApiResponse<SupervisorAsyncWorkflowCreateResponse> startSupervisorWorkflowAsync(@RequestBody SupervisorTaskRequest request)` |
| 503 | `@GetMapping("/supervisor/workflows/async/status")` | `public ApiResponse<SupervisorAsyncWorkflowStatusResponse> querySupervisorAsyncWorkflowStatus(@RequestParam("asyncWorkflowId") String asyncWorkflowId, @RequestParam("userId") String userId)` |
| 514 | `@GetMapping("/supervisor/workflows/async/stream")` | `public SseEmitter streamSupervisorAsyncWorkflow(@RequestParam String asyncWorkflowId, @RequestParam String userId, @RequestParam(name = "afterEventId", required = false) String afterEventId, @RequestParam(required = false) Long afterSequence, @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId)` |
| 525 | `@PostMapping("/supervisor/workflows/async/cancel")` | `public ApiResponse<SupervisorAsyncWorkflowStatusResponse> cancelSupervisorAsyncWorkflow(@RequestParam String asyncWorkflowId, @RequestParam String userId)` |
| 537 | `@PostMapping("/supervisor/workflows/async/retry")` | `public ApiResponse<SupervisorAsyncWorkflowCreateResponse> retrySupervisorAsyncWorkflow(@RequestParam String asyncWorkflowId, @RequestParam String userId)` |
| 556 | `@PostMapping("/supervisor/approvals/callback")` | `public ApiResponse<SupervisorTaskResponse> handleApprovalCallback(@RequestBody ApprovalCallbackRequest request)` |
| 571 | `@GetMapping("/supervisor/approvals/pending")` | `public ApiResponse<Map<String, Object>> listPendingApprovals( @RequestParam(name = "userId", required = false) String userId, @RequestParam(name = "page", defaultValue = "1") int page, @RequestParam(name = "pageSize", defaultValue = "20") int pageSize)` |
| 583 | `@GetMapping("/supervisor/workflows/state")` | `public ApiResponse<SupervisorWorkflowView> getWorkflowState(@RequestParam("taskId") String taskId, @RequestParam("userId") String userId)` |
| 590 | `@GetMapping("/supervisor/workflows/artifacts")` | `public ApiResponse<List<TaskArtifactView>> listWorkflowArtifacts(@RequestParam("taskId") String taskId, @RequestParam("userId") String userId)` |
| 601 | `@GetMapping("/supervisor/diagnostics")` | `public ApiResponse<SupervisorDiagnosticsView> diagnostics(@RequestParam String userId, @RequestParam(name = "taskId", required = false) String taskId, @RequestParam(name = "traceId", required = false) String traceId, @RequestParam(name = "approvalId", required = false) String approvalId, @RequestParam(name = "artifactId", required = false) String artifactId, @RequestParam(name = "asyncTaskId", required = false) String asyncTaskId, @RequestParam(name = "asyncWorkflowId", required = false) String asyncWorkflowId, @RequestParam(name = "grayStrategyVersion", required = false) String grayStrategyVersion)` |
| 616 | `@GetMapping("/supervisor/agents/catalog")` | `public ApiResponse<List<AgentCatalogView>> supervisorAgentCatalog()` |
| 621 | `@GetMapping("/supervisor/event-audit")` | `public ApiResponse<List<SessionEventAuditView>> eventAudit(@RequestParam(name = "taskId", required = false) String taskId, @RequestParam(name = "traceId", required = false) String traceId, @RequestParam(name = "approvalId", required = false) String approvalId, @RequestParam(name = "artifactId", required = false) String artifactId, @RequestParam(name = "asyncTaskId", required = false) String asyncTaskId, @RequestParam(name = "grayStrategyVersion", required = false) String grayStrategyVersion, @RequestParam(name = "asyncWorkflowId", required = false) String asyncWorkflowId, @RequestParam(name = "includeArchived", required = false) Boolean includeArchived, @RequestParam(name = "limit", required = false) Integer limit)` |
| 636 | `@GetMapping("/supervisor/event-audit/business")` | `public ApiResponse<List<SessionEventAuditView>> businessEventAudit( @RequestParam(name = "taskId", required = false) String taskId, @RequestParam(name = "traceId", required = false) String traceId, @RequestParam(name = "approvalId", required = false) String approvalId, @RequestParam(name = "artifactId", required = false) String artifactId, @RequestParam(name = "asyncTaskId", required = false) String asyncTaskId, @RequestParam(name = "grayStrategyVersion", required = false) String grayStrategyVersion, @RequestParam(name = "asyncWorkflowId", required = false) String asyncWorkflowId, @RequestParam(name = "includeArchived", required = false) Boolean includeArchived, @RequestParam(name = "limit", required = false) Integer limit)` |
| 647 | `@GetMapping("/supervisor/governance")` | `public ApiResponse<SupervisorGovernanceView> governance()` |
| 652 | `@PostMapping("/supervisor/governance/rate-limit")` | `public ApiResponse<SupervisorGovernanceView> overrideRateLimit(@RequestBody GovernanceRateLimitOverrideRequest request)` |
| 657 | `@PostMapping("/supervisor/governance/rate-limit/clear")` | `public ApiResponse<SupervisorGovernanceView> clearRateLimitOverride(@RequestParam(name = "entryType", required = false) String entryType)` |
| 662 | `@PostMapping("/supervisor/governance/gray-release")` | `public ApiResponse<SupervisorGovernanceView> overrideGrayRelease(@RequestBody GovernanceGrayReleaseOverrideRequest request)` |
| 667 | `@PostMapping("/supervisor/governance/gray-release/clear")` | `public ApiResponse<SupervisorGovernanceView> clearGrayReleaseOverride()` |
| 707 | `@GetMapping("/supervisor/stream")` | `public SseEmitter subscribeSupervisorStream(@RequestParam("sessionId") String sessionId, @RequestParam(value = "taskId", required = false) String taskId, @RequestParam(value = "afterEventId", required = false) String afterEventId, @RequestParam(value = "afterSequence", required = false) Long afterSequence, @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId)` |
| 725 | `@GetMapping("/supervisor/agents")` | `public ApiResponse<List<AgentCard>> listSupervisorAgents()` |
| 733 | `@GetMapping("/health")` | `public ApiResponse<HealthStatusDTO> health()` |

### [StudioController.java](../agent-service/src/main/java/com/bkanent/agent/studio/StudioController.java)
类级路径：`/agent/studio/v1`

| 行 | 映射注解 | 方法声明（字段定义见源码） |
| --- | --- | --- |
| 24 | `@GetMapping("/capabilities")` | `public ApiResponse<List<Map<String, Object>>> capabilities()` |
| 25 | `@PostMapping("/works")` | `public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body)` |
| 26 | `@GetMapping("/works")` | `public ApiResponse<Map<String, Object>> recent(@RequestParam(required = false) String keyword, @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) String status, @RequestParam(required = false) String needsDecision)` |
| 31 | `@GetMapping("/works/{id}")` | `public ApiResponse<Map<String, Object>> work(@PathVariable String id)` |
| 42 | `@PostMapping("/works/{id}/context")` | `public ApiResponse<Map<String, Object>> context(@PathVariable String id, @RequestBody Map<String, Object> body)` |
| 43 | `@PostMapping("/works/{id}/ui-state")` | `public ApiResponse<Map<String, Object>> ui(@PathVariable String id, @RequestBody Map<String, Object> body)` |
| 44 | `@PostMapping("/works/{id}/runs")` | `public ApiResponse<Map<String, Object>> submit(@PathVariable String id, @RequestBody Map<String, Object> body)` |
| 45 | `@GetMapping("/works/{id}/runs")` | `public ApiResponse<Map<String, Object>> runs(@PathVariable String id, @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit)` |
| 46 | `@GetMapping("/works/{id}/messages")` | `public ApiResponse<Map<String, Object>> messages(@PathVariable String id, @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "100") int limit)` |
| 47 | `@GetMapping("/works/{id}/artifacts")` | `public ApiResponse<Map<String, Object>> artifacts(@PathVariable String id, @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "100") int limit)` |
| 48 | `@GetMapping("/artifact-series/{seriesId}")` | `public ApiResponse<Map<String, Object>> artifact(@PathVariable String seriesId, @RequestParam(required = false) Long version)` |
| 49 | `@PostMapping("/artifact-series/{seriesId}/revisions")` | `public ApiResponse<Map<String, Object>> save(@PathVariable String seriesId, @RequestBody Map<String, Object> body)` |
| 50 | `@GetMapping("/artifact-series/{seriesId}/revisions")` | `public ApiResponse<Map<String, Object>> revisions(@PathVariable String seriesId, @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "100") int limit)` |
| 51 | `@PostMapping("/works/{id}/commands")` | `public ApiResponse<Map<String, Object>> command(@PathVariable String id, @RequestBody Map<String, Object> body)` |
| 52 | `@GetMapping("/works/{id}/commands/{requestId}")` | `public ApiResponse<Map<String, Object>> receipt(@PathVariable String id, @PathVariable String requestId)` |

## listing-master-service

### [ListingBusinessSourceController.java](../listing-master-service/src/main/java/com/bkanent/listing/controller/ListingBusinessSourceController.java)
类级路径：`/listings/business/v1`

| 行 | 映射注解 | 方法声明（字段定义见源码） |
| --- | --- | --- |
| 32 | `@PostMapping("/search")` | `public ApiResponse<ListingBusinessResponse<ListingSourceSnapshot>> search( @RequestBody ListingBusinessSearchRequest request)` |
| 38 | `@PostMapping("/snapshots/batch-read")` | `public ApiResponse<ListingBusinessResponse<ListingSnapshotBatchReadItem>> batchRead( @RequestBody ListingSnapshotBatchReadRequest request)` |
| 44 | `@PostMapping("/snapshots/check")` | `public ApiResponse<ListingBusinessResponse<ListingSnapshotCheckItem>> check( @RequestBody ListingSnapshotCheckRequest request)` |

### [ListingComparisonController.java](../listing-master-service/src/main/java/com/bkanent/listing/controller/ListingComparisonController.java)
类级路径：`/listings/comparisons`

| 行 | 映射注解 | 方法声明（字段定义见源码） |
| --- | --- | --- |
| 31 | `@PostMapping` | `public ApiResponse<ListingComparisonReportResponse> create(@RequestBody ListingComparisonCreateRequest request)` |
| 36 | `@GetMapping("/{reportId}")` | `public ApiResponse<ListingComparisonReportResponse> get(@PathVariable String reportId)` |
| 41 | `@GetMapping("/{reportId}/pdf")` | `public ResponseEntity<Resource> pdf(@PathVariable String reportId)` |

### [ListingComparisonLegacyController.java](../listing-master-service/src/main/java/com/bkanent/listing/controller/ListingComparisonLegacyController.java)
类级路径：`/compare`

| 行 | 映射注解 | 方法声明（字段定义见源码） |
| --- | --- | --- |
| 26 | `@GetMapping("/listings")` | `public ApiResponse<ListingComparisonLegacySummaryResponse> compareListings(@RequestParam List<Long> listingIds)` |
| 31 | `@GetMapping("/reports")` | `public ApiResponse<ListingComparisonLegacyReportResponse> createReport( @RequestParam List<Long> listingIds, @RequestParam(defaultValue = "true") boolean includeAiConclusion)` |

### [ListingController.java](../listing-master-service/src/main/java/com/bkanent/listing/controller/ListingController.java)
类级路径：`/listings`

| 行 | 映射注解 | 方法声明（字段定义见源码） |
| --- | --- | --- |
| 40 | `@PostMapping` | `public ApiResponse<ListingDetailResponse> create(@RequestBody ListingUpsertRequest request)` |
| 45 | `@PutMapping("/{id}")` | `public ApiResponse<ListingDetailResponse> update(@PathVariable("id") Long id, @RequestBody ListingUpsertRequest request)` |
| 50 | `@DeleteMapping("/{id}")` | `public ApiResponse<Void> delete(@PathVariable("id") Long id)` |
| 56 | `@GetMapping("/{id}")` | `public ApiResponse<ListingDetailResponse> detail(@PathVariable("id") Long id)` |
| 61 | `@GetMapping("/search")` | `public ApiResponse<List<ListingDetailResponse>> search(@RequestParam(name = "keyword", required = false) String keyword, @RequestParam(name = "status", required = false) String status, @RequestParam(name = "brokerId", required = false) Long brokerId, @RequestParam(name = "minArea", required = false) BigDecimal minArea, @RequestParam(name = "maxArea", required = false) BigDecimal maxArea, @RequestParam(name = "minTotalPrice", required = false) BigDecimal minTotalPrice, @RequestParam(name = "maxTotalPrice", required = false) BigDecimal maxTotalPrice, @RequestParam(name = "verifiedOnly", required = false) Boolean verifiedOnly)` |
| 82 | `@GetMapping` | `public ApiResponse<List<ListingDTO>> searchSummary(@RequestParam(name = "keyword", required = false, defaultValue = "") String keyword)` |
| 87 | `@PutMapping("/{id}/status")` | `public ApiResponse<ListingDetailResponse> updateStatus(@PathVariable("id") Long id, @RequestBody ListingStatusUpdateRequest request)` |
| 93 | `@PutMapping("/{id}/assets")` | `public ApiResponse<ListingDetailResponse> bindAssets(@PathVariable("id") Long id, @RequestBody ListingAssetBindRequest request)` |
| 99 | `@PostMapping("/{id}/ocr")` | `public ApiResponse<ListingDetailResponse> recognizeOcr(@PathVariable("id") Long id, @RequestBody ListingOcrRecognizeRequest request)` |
| 105 | `@PostMapping("/{id}/verify")` | `public ApiResponse<ListingDetailResponse> verify(@PathVariable("id") Long id, @RequestBody ListingVerifyRequest request)` |
| 111 | `@GetMapping("/health")` | `public ApiResponse<HealthStatusDTO> health()` |

### [ListingRagController.java](../listing-master-service/src/main/java/com/bkanent/listing/controller/ListingRagController.java)
类级路径：`/listings/rag`

| 行 | 映射注解 | 方法声明（字段定义见源码） |
| --- | --- | --- |
| 20 | `@PostMapping("/search")` | `public ApiResponse<RagListingSearchResponse> search(@RequestHeader(value = "Authorization", required = false) String authorization, @RequestBody RagListingSearchRequest request)` |
| 25 | `@PostMapping("/backfill")` | `public ApiResponse<List<QueuedEvent>> backfill(@RequestHeader(value = "Authorization", required = false) String authorization, @RequestParam(defaultValue = "0") long afterId, @RequestParam(defaultValue = "100") int limit)` |
| 32 | `@PostMapping("/index/{listingId}")` | `public ApiResponse<RagIndexStatus> index(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable("listingId") Long listingId)` |

## customer-service

### [CustomerController.java](../customer-service/src/main/java/com/bkanent/customer/controller/CustomerController.java)
类级路径：`/customers`

| 行 | 映射注解 | 方法声明（字段定义见源码） |
| --- | --- | --- |
| 47 | `@PostMapping` | `public ApiResponse<CustomerProfileDTO> create(@RequestBody CustomerUpsertRequest request)` |
| 52 | `@PutMapping("/{id}")` | `public ApiResponse<CustomerProfileDTO> update(@PathVariable Long id, @RequestBody CustomerUpsertRequest request)` |
| 57 | `@DeleteMapping("/{id}")` | `public ApiResponse<Void> delete(@PathVariable Long id)` |
| 63 | `@GetMapping("/{id}")` | `public ApiResponse<CustomerDetailResponse> detail(@PathVariable Long id)` |
| 68 | `@GetMapping` | `public ApiResponse<List<CustomerProfileDTO>> search(@RequestParam(required = false) String profileType, @RequestParam(required = false) String keyword, @RequestParam(required = false) Long brokerId, @RequestParam(required = false) String intention, @RequestParam(required = false) BigDecimal budgetMin, @RequestParam(required = false) BigDecimal budgetMax)` |
| 85 | `@PostMapping("/{id}/follow-records")` | `public ApiResponse<CustomerFollowRecordResponse> addFollowRecord(@PathVariable Long id, @RequestBody CustomerFollowRecordRequest request)` |
| 91 | `@GetMapping("/{id}/follow-records")` | `public ApiResponse<List<CustomerFollowRecordResponse>> listFollowRecords(@PathVariable Long id)` |
| 96 | `@GetMapping("/{id}/matched-listings")` | `public ApiResponse<List<CustomerMatchResponse>> matchedListings(@PathVariable Long id)` |
| 101 | `@PostMapping("/{id}/entrusts")` | `public ApiResponse<OwnerEntrustResponse> saveEntrust(@PathVariable Long id, @RequestBody OwnerEntrustUpsertRequest request)` |
| 107 | `@GetMapping("/{id}/entrusts")` | `public ApiResponse<List<OwnerEntrustResponse>> listEntrusts(@PathVariable Long id)` |
| 112 | `@GetMapping("/entrusts/expiring")` | `public ApiResponse<List<OwnerEntrustResponse>> expiringEntrusts(@RequestParam(defaultValue = "7") int days)` |
| 117 | `@PostMapping("/entrusts/remind")` | `public ApiResponse<Integer> remindExpiringEntrusts(@RequestParam(defaultValue = "7") int days)` |
| 126 | `@PostMapping("/{id}/favorites")` | `public ApiResponse<CustomerFavoriteResponse> addFavorite(@PathVariable Long id, @RequestBody CustomerFavoriteRequest request)` |
| 132 | `@DeleteMapping("/{id}/favorites/{listingId}")` | `public ApiResponse<Void> removeFavorite(@PathVariable Long id, @PathVariable Long listingId)` |
| 138 | `@GetMapping("/{id}/favorites")` | `public ApiResponse<List<CustomerFavoriteResponse>> listFavorites(@PathVariable Long id)` |
| 143 | `@GetMapping("/health")` | `public ApiResponse<HealthStatusDTO> health()` |

## platform-operations-service

### [BusinessController.java](../platform-operations-service/src/main/java/com/bkanent/business/controller/BusinessController.java)
类级路径：`/platform-operations/analytics`

| 行 | 映射注解 | 方法声明（字段定义见源码） |
| --- | --- | --- |
| 34 | `@GetMapping("/kpis/monthly")` | `public ApiResponse<List<KpiSummaryDTO>> monthlyKpis(@RequestParam("month") String month)` |
| 39 | `@GetMapping("/workloads/daily")` | `public ApiResponse<List<EmployeeDailyWorkloadResponse>> dailyWorkloads(@RequestParam("statDate") String statDate)` |
| 44 | `@GetMapping("/kpis/assessments")` | `public ApiResponse<List<KpiAssessmentResponse>> assessments(@RequestParam("month") String month)` |
| 49 | `@GetMapping("/rankings")` | `public ApiResponse<List<RankingItemResponse>> rankings(@RequestParam("month") String month, @RequestParam("scope") String scope, @RequestParam(name = "topN", defaultValue = "10") int topN)` |
| 56 | `@PostMapping("/rankings/rebuild")` | `public ApiResponse<Void> rebuildRankings(@RequestParam("month") String month)` |
| 62 | `@GetMapping("/listing-turnover-report")` | `public ApiResponse<List<ListingTurnoverReportResponse>> turnoverReports(@RequestParam("month") String month)` |
| 67 | `@GetMapping("/store-dashboard")` | `public ApiResponse<StoreDashboardResponse> storeDashboard(@RequestParam("storeName") String storeName)` |
| 72 | `@GetMapping("/big-dashboard")` | `public ApiResponse<BigDataDashboardResponse> bigDashboard(@RequestParam("month") String month, @RequestParam("startDate") String startDate, @RequestParam("endDate") String endDate, @RequestParam(name = "regionName", required = false) String regionName, @RequestParam(name = "topN", defaultValue = "5") int topN)` |
| 81 | `@GetMapping("/health")` | `public ApiResponse<HealthStatusDTO> health()` |

### [ReviewController.java](../platform-operations-service/src/main/java/com/bkanent/businessreview/web/ReviewController.java)
类级路径：`/platform-operations/reviews`

| 行 | 映射注解 | 方法声明（字段定义见源码） |
| --- | --- | --- |
| 19 | `@PostMapping` | `public ApiResponse<ReviewMatter> create(@RequestBody CreateReviewRequest request)` |
| 24 | `@GetMapping` | `public ApiResponse<CursorPage<ReviewMatter>> list(@RequestParam("storeId") Long storeId, @RequestParam(name = "cursor", required = false) String cursor, @RequestParam(name = "limit", defaultValue = "20") int limit)` |
| 31 | `@GetMapping("/{reviewId}")` | `public ApiResponse<ReviewMatter> get(@PathVariable String reviewId)` |
| 36 | `@GetMapping("/{reviewId}/diagnosis-version")` | `public ApiResponse<ReviewApplicationService.DiagnosisVersion> diagnosisVersion(@PathVariable String reviewId)` |
| 41 | `@GetMapping("/{reviewId}/plans")` | `public ApiResponse<List<ReviewPlan>> plans(@PathVariable String reviewId)` |
| 46 | `@PostMapping("/{reviewId}/plans")` | `public ApiResponse<ReviewPlan> writePlan(@PathVariable String reviewId, @RequestBody WritePlanRequest request)` |
| 53 | `@PostMapping("/{reviewId}/plans/confirm")` | `public ApiResponse<ConfirmationReceipt> confirm(@PathVariable String reviewId, @RequestBody ConfirmPlanRequest request)` |
| 60 | `@GetMapping("/{reviewId}/actions")` | `public ApiResponse<List<ReviewAction>> actions(@PathVariable String reviewId)` |
| 65 | `@PostMapping("/{reviewId}/actions/{actionId}/feedback")` | `public ApiResponse<ReviewAction> feedback(@PathVariable String reviewId, @PathVariable String actionId, @RequestBody ActionFeedbackRequest request)` |
| 72 | `@GetMapping("/{reviewId}/observations")` | `public ApiResponse<ReviewObservation> observations(@PathVariable String reviewId)` |

### [KnowledgeCaseController.java](../platform-operations-service/src/main/java/com/bkanent/knowledge/controller/KnowledgeCaseController.java)
类级路径：`/platform-operations/knowledge/v1/cases`

| 行 | 映射注解 | 方法声明（字段定义见源码） |
| --- | --- | --- |
| 32 | `@PostMapping` | `public ApiResponse<KnowledgeMutationReceipt> create(@RequestBody CreateKnowledgeRequest request)` |
| 36 | `@GetMapping("/{knowledgeId}")` | `public ApiResponse<KnowledgeCaseDetail> read(@PathVariable String knowledgeId)` |
| 40 | `@PostMapping("/{knowledgeId}/edit")` | `public ApiResponse<KnowledgeMutationReceipt> edit(@PathVariable String knowledgeId, @RequestBody EditKnowledgeRequest request)` |
| 44 | `@PostMapping("/{knowledgeId}/submit")` | `public ApiResponse<KnowledgeMutationReceipt> submit(@PathVariable String knowledgeId, @RequestBody KnowledgeOperationRequest request)` |
| 48 | `@PostMapping("/{knowledgeId}/generate")` | `public ApiResponse<KnowledgeGenerationStatus> generate(@PathVariable String knowledgeId, @RequestBody GenerateKnowledgeRequest request)` |
| 52 | `@GetMapping("/{knowledgeId}/generation/{taskId}")` | `public ApiResponse<KnowledgeGenerationStatus> generationStatus(@PathVariable String knowledgeId, @PathVariable String taskId)` |
| 56 | `@PostMapping("/{knowledgeId}/review")` | `public ApiResponse<KnowledgeMutationReceipt> review(@PathVariable String knowledgeId, @RequestBody KnowledgeReviewRequest request)` |
| 60 | `@PostMapping("/{knowledgeId}/publish")` | `public ApiResponse<KnowledgeMutationReceipt> publish(@PathVariable String knowledgeId, @RequestBody KnowledgeOperationRequest request)` |
| 64 | `@PostMapping("/{knowledgeId}/withdraw")` | `public ApiResponse<KnowledgeMutationReceipt> withdraw(@PathVariable String knowledgeId, @RequestBody KnowledgeOperationRequest request)` |

### [PublishedKnowledgeController.java](../platform-operations-service/src/main/java/com/bkanent/knowledge/controller/PublishedKnowledgeController.java)
类级路径：`/platform-operations/knowledge/v1/published`

| 行 | 映射注解 | 方法声明（字段定义见源码） |
| --- | --- | --- |
| 23 | `@PostMapping("/search")` | `public ApiResponse<PublishedSearchData> search(@RequestBody PublishedSearchRequest request)` |
| 28 | `@PostMapping("/batch-read")` | `public ApiResponse<PublishedBatchReadData> batchRead(@RequestBody PublishedBatchReadRequest request)` |
| 37 | `@PostMapping("/check")` | `public ApiResponse<PublishedCheckData> check(@RequestBody PublishedCheckRequest request)` |

## marketing-content-service

### [CampaignController.java](../marketing-content-service/src/main/java/com/bkanent/marketing/controller/CampaignController.java)
类级路径：`/marketing/campaigns`

| 行 | 映射注解 | 方法声明（字段定义见源码） |
| --- | --- | --- |
| 38 | `@PostMapping("/prepare")` | `public ApiResponse<CampaignPlanResponse> prepare(@RequestBody CampaignPlanPrepareRequest request)` |
| 43 | `@PostMapping("/contents/{contentId}/review")` | `public ApiResponse<MarketingContentDetailResponse> reviewContent(@PathVariable long contentId, @RequestBody CampaignContentReviewRequest request)` |
| 49 | `@GetMapping("/plans/{planId}")` | `public ApiResponse<CampaignPlanResponse> getPlan(@PathVariable String planId)` |
| 54 | `@PostMapping("/plans/{planId}/confirm")` | `public ApiResponse<CampaignReceiptResponse> confirm(@PathVariable String planId, @RequestBody CampaignConfirmRequest request)` |
| 60 | `@GetMapping("/plans/{planId}/receipts/{requestId}")` | `public ApiResponse<CampaignReceiptResponse> receipt(@PathVariable String planId, @PathVariable String requestId)` |
| 65 | `@GetMapping("/receipts/{receiptId}")` | `public ApiResponse<CampaignReceiptResponse> receipt(@PathVariable String receiptId)` |
| 70 | `@PostMapping("/attempts/{attemptId}/reconcile")` | `public ApiResponse<CampaignReceiptResponse> reconcile(@PathVariable String attemptId)` |
| 75 | `@GetMapping("/attempts/{attemptId}/effects")` | `public ApiResponse<CampaignEffectResponse> effect(@PathVariable String attemptId)` |
| 80 | `@PostMapping("/attempts/{attemptId}/effects/sync")` | `public ApiResponse<CampaignEffectResponse> syncEffect(@PathVariable String attemptId)` |
| 85 | `@GetMapping("/roi-report")` | `public ApiResponse<List<CampaignRoiReportResponse>> roiReport(@RequestParam(required = false) String platform)` |
| 90 | `@PostMapping("/brand-assets")` | `public ApiResponse<CampaignBrandAssetResponse> createAsset(@RequestBody CampaignBrandAssetRequest request)` |
| 95 | `@GetMapping("/brand-assets")` | `public ApiResponse<List<CampaignBrandAssetResponse>> listAssets(@RequestParam(required = false) String assetType, @RequestParam(required = false) String keyword)` |

### [LegacyPromotionsController.java](../marketing-content-service/src/main/java/com/bkanent/marketing/controller/LegacyPromotionsController.java)
类级路径：`/promotions`

| 行 | 映射注解 | 方法声明（字段定义见源码） |
| --- | --- | --- |
| 40 | `@PostMapping("/publish")` | `public ApiResponse<CampaignPlanResponse> prepare(@RequestBody JsonNode body)` |
| 58 | `@PostMapping("/{planId}/confirm")` | `public ApiResponse<CampaignReceiptResponse> confirm(@PathVariable String planId, @RequestBody CampaignConfirmRequest request)` |
| 64 | `@GetMapping("/receipts/{receiptId}")` | `public ApiResponse<CampaignReceiptResponse> receipt(@PathVariable String receiptId)` |
| 69 | `@PostMapping("/effects/sync")` | `public ApiResponse<CampaignEffectResponse> syncEffect(@RequestBody LegacyEffectSyncRequest request)` |
| 75 | `@GetMapping("/effects")` | `public ApiResponse<List<CampaignEffectResponse>> effects(@RequestParam Long listingId)` |
| 80 | `@GetMapping("/roi-report")` | `public ApiResponse<List<CampaignRoiReportResponse>> roiReport()` |
| 83 | `@PostMapping("/brand-assets")` | `public ApiResponse<CampaignBrandAssetResponse> saveBrandAsset(@RequestBody CampaignBrandAssetRequest request)` |
| 88 | `@GetMapping("/brand-assets")` | `public ApiResponse<List<CampaignBrandAssetResponse>> brandAssets(@RequestParam(required = false) String assetType, @RequestParam(required = false) String keyword)` |
| 94 | `@GetMapping("/health")` | `public ApiResponse<String> health()` |

### [MarketingContentController.java](../marketing-content-service/src/main/java/com/bkanent/marketing/controller/MarketingContentController.java)
类级路径：`/marketing`

| 行 | 映射注解 | 方法声明（字段定义见源码） |
| --- | --- | --- |
| 35 | `@PostMapping("/contents")` | `public ApiResponse<MarketingContentDetailResponse> create(@RequestBody MarketingContentUpsertRequest request)` |
| 40 | `@PostMapping("/contents/{id}/variants")` | `public ApiResponse<MarketingContentDetailResponse> createVariant(@PathVariable Long id, @RequestBody MarketingContentUpsertRequest request)` |
| 46 | `@PutMapping("/contents/{id}/publish-status")` | `public ApiResponse<MarketingContentDetailResponse> updatePublishStatus(@PathVariable Long id, @RequestBody MarketingPublishStatusUpdateRequest request)` |
| 52 | `@GetMapping("/contents")` | `public ApiResponse<List<MarketingContentDTO>> list(@RequestParam Long listingId)` |
| 57 | `@GetMapping("/contents/search")` | `public ApiResponse<List<MarketingContentDetailResponse>> search(@RequestParam(required = false) Long listingId, @RequestParam(required = false) String platform, @RequestParam(required = false) String keyword, @RequestParam(required = false) String tag, @RequestParam(required = false) String contentType, @RequestParam(required = false) String auditStatus, @RequestParam(required = false) String publishStatus)` |
| 76 | `@GetMapping("/health")` | `public ApiResponse<HealthStatusDTO> health()` |

## notification-service

### [NotificationController.java](../notification-service/src/main/java/com/bkanent/notification/controller/NotificationController.java)
类级路径：`/notifications`

| 行 | 映射注解 | 方法声明（字段定义见源码） |
| --- | --- | --- |
| 39 | `@PostMapping("/station")` | `public ApiResponse<Long> station(@RequestBody NotificationMessageRequest request)` |
| 44 | `@PostMapping("/email")` | `public ApiResponse<Long> email(@RequestBody NotificationMessageRequest request)` |
| 49 | `@PostMapping("/robot")` | `public ApiResponse<Long> robot(@RequestBody RobotMessageRequest request)` |
| 54 | `@GetMapping` | `public ApiResponse<List<NotificationListItemResponse>> list(@RequestParam(required = false) Long userId, @RequestParam(required = false) String channel, @RequestParam(required = false) String readStatus)` |
| 61 | `@PostMapping("/{id}/read")` | `public ApiResponse<Void> markRead(@PathVariable Long id, @RequestBody NotificationReadRequest request)` |
| 67 | `@GetMapping("/unread-count")` | `public ApiResponse<Long> unreadCount(@RequestParam(required = false) Long userId)` |
| 72 | `@GetMapping("/workflow-events")` | `public ApiResponse<List<NotificationWorkflowEventConsumeResponse>> workflowEvents( @RequestParam(required = false) String consumeStatus, @RequestParam(required = false) String eventType, @RequestParam(required = false) String taskId, @RequestParam(required = false) Long recipientUserId, @RequestParam(required = false) Integer limit)` |
| 89 | `@GetMapping("/health")` | `public ApiResponse<HealthStatusDTO> health()` |

## interview-service

### [InterviewAssetController.java](../interview-service/src/main/java/com/bkanent/interview/runtime/InterviewAssetController.java)
类级路径：`/interviews/assets`

| 行 | 映射注解 | 方法声明（字段定义见源码） |
| --- | --- | --- |
| 25 | `@PostMapping` | `public ApiResponse<Map<String, Object>> submit(@RequestBody SubmitTranscriptRequest request)` |
| 38 | `@PostMapping("/{assetId}/publish")` | `public ApiResponse<Map<String, Object>> publish(@PathVariable Long assetId)` |
| 49 | `@PostMapping("/{assetId}/revoke-publication")` | `public ApiResponse<Map<String, Object>> revokePublication(@PathVariable Long assetId)` |
| 63 | `@PostMapping("/{assetId}/regrade")` | `public ApiResponse<Map<String, Object>> regrade(@PathVariable Long assetId)` |

### [InterviewController.java](../interview-service/src/main/java/com/bkanent/interview/runtime/InterviewController.java)
类级路径：`/interviews`

| 行 | 映射注解 | 方法声明（字段定义见源码） |
| --- | --- | --- |
| 152 | `@GetMapping` | `public ApiResponse<List<Map<String, Object>>> listSessions(@RequestParam(value = "status", required = false) String status)` |
| 184 | `@GetMapping("/participants/invitations")` | `public ApiResponse<List<Map<String, Object>>> pendingInvitations()` |
| 204 | `@PostMapping("/{sessionId}/participants/respond/invite")` | `public ApiResponse<Map<String, Object>> inviteRespondent(@PathVariable("sessionId") Long sessionId, @RequestBody InviteRespondentRequest request)` |
| 232 | `@PostMapping` | `public ApiResponse<Map<String, Object>> openCase(@RequestBody OpenCaseRequest request)` |
| 256 | `@PostMapping("/{sessionId}/credentials")` | `public ResponseEntity<ApiResponse<Map<String, Object>>> issueParticipantCredential( @PathVariable("sessionId") Long sessionId, @RequestBody ParticipantCredentialRequest request)` |
| 300 | `@PostMapping("/{sessionId}/participants/respond/confirm")` | `public ApiResponse<Map<String, Object>> confirmRespondent(@PathVariable("sessionId") Long sessionId)` |
| 320 | `@PostMapping("/{sessionId}/questions")` | `public ApiResponse<Map<String, Object>> createQuestions( @PathVariable("sessionId") Long sessionId, @RequestHeader(value = "x-ticket", required = false) String ticket, @RequestBody CreateQuestionsRequest request)` |
| 350 | `@PostMapping("/{sessionId}/questions/confirm")` | `public ApiResponse<Map<String, Object>> confirmQuestions( @PathVariable("sessionId") Long sessionId, @RequestHeader(value = "x-ticket", required = false) String ticket, @RequestBody ConfirmQuestionsRequest request)` |
| 374 | `@PostMapping("/{sessionId}/start")` | `public ApiResponse<Map<String, Object>> start( @PathVariable("sessionId") Long sessionId, @RequestHeader(value = "x-ticket", required = false) String ticket)` |
| 400 | `@GetMapping("/{sessionId}")` | `public ApiResponse<InterviewMonitorResponse> monitor( @PathVariable("sessionId") Long sessionId, @RequestHeader(value = "x-ticket", required = false) String ticket)` |
| 428 | `@PostMapping(value = "/{sessionId}/turns", produces = MediaType.TEXT_EVENT_STREAM_VALUE)` | `public Flux<ServerSentEvent<String>> submitTurn( @PathVariable("sessionId") Long sessionId, @RequestHeader(value = "x-req-id", required = false) String reqId, @RequestHeader(value = "x-ticket", required = false) String ticket, @RequestBody TurnRequest request)` |
| 470 | `@GetMapping("/{sessionId}/replies/{reqId}")` | `public ApiResponse<Map<String, Object>> reply( @PathVariable("sessionId") Long sessionId, @PathVariable("reqId") String reqId, @RequestHeader(value = "x-ticket", required = false) String ticket)` |
| 498 | `@GetMapping(value = "/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)` | `public Flux<ServerSentEvent<String>> stream( @PathVariable("sessionId") Long sessionId, @RequestHeader(value = "x-ticket", required = false) String ticket)` |
| 531 | `@GetMapping("/reports/tasks")` | `public ApiResponse<Map<String, Object>> listReportTasks( @RequestParam(value = "creatorWorkNo", required = false) String creatorWorkNo, @RequestParam(value = "status", required = false) String status, @RequestParam(value = "page", defaultValue = "1") int page, @RequestParam(value = "pageSize", defaultValue = "20") int pageSize)` |
| 551 | `@GetMapping("/reports/tasks/{taskId}")` | `public ApiResponse<Map<String, Object>> reportTaskDetail( @PathVariable("taskId") Long taskId)` |
| 575 | `@GetMapping("/reports/search")` | `public ApiResponse<Map<String, Object>> searchReports( @RequestParam(value = "keyword", required = false) String keyword, @RequestParam(value = "creatorWorkNo", required = false) String creatorWorkNo, @RequestParam(value = "page", defaultValue = "1") int page, @RequestParam(value = "pageSize", defaultValue = "20") int pageSize)` |
| 597 | `@PostMapping("/{sessionId}/director-commands")` | `public ApiResponse<Map<String, Object>> sendDirectorCommand( @PathVariable("sessionId") Long sessionId, @RequestHeader(value = "x-ticket", required = false) String ticket, @RequestBody DirectorCommandRequest request)` |

## media-worker-service

### [MediaWorkerController.java](../media-worker-service/src/main/java/com/bkanent/media/controller/MediaWorkerController.java)
类级路径：`/media/tasks`

| 行 | 映射注解 | 方法声明（字段定义见源码） |
| --- | --- | --- |
| 28 | `@PostMapping` | `public ApiResponse<String> submit(@RequestBody MediaGenerateTaskRequest request)` |
| 33 | `@GetMapping("/{taskId}")` | `public ApiResponse<MediaTaskResultDTO> detail(@PathVariable String taskId)` |
| 38 | `@GetMapping("/health")` | `public ApiResponse<HealthStatusDTO> health()` |

## memory-service

### [MemoryController.java](../memory-service/src/main/java/com/bkanent/memory/controller/MemoryController.java)
类级路径：`/internal/memory`

| 行 | 映射注解 | 方法声明（字段定义见源码） |
| --- | --- | --- |
| 71 | `@PostMapping("/sessions/upsert")` | `public ApiResponse<Void> upsertSessionMemory(@RequestBody SessionMemoryUpsertRequest request)` |
| 80 | `@GetMapping("/sessions")` | `public ApiResponse<SessionMemoryResponse> getSessionMemory(@RequestParam("sessionId") String sessionId)` |
| 85 | `@PostMapping("/artifacts")` | `public ApiResponse<ArtifactQueryResponse> createArtifact(@RequestBody ArtifactCreateRequest request)` |
| 91 | `@GetMapping("/artifacts/by-task")` | `public ApiResponse<List<ArtifactQueryResponse>> listArtifactsByTask(@RequestParam("taskId") String taskId, @RequestParam("sessionId") String sessionId)` |
| 100 | `@GetMapping("/artifacts/by-id")` | `public ApiResponse<ArtifactQueryResponse> getArtifactById(@RequestParam("artifactId") String artifactId, @RequestParam("taskId") String taskId, @RequestParam(value = "sessionId", required = false) String sessionId)` |
| 111 | `@PostMapping("/handoffs")` | `public ApiResponse<HandoffRelationQueryResponse> createHandoffRelation(@RequestBody AgentHandoffPacket packet)` |
| 117 | `@GetMapping("/handoffs/by-task")` | `public ApiResponse<List<HandoffRelationQueryResponse>> listHandoffsByTask(@RequestParam("taskId") String taskId)` |
| 125 | `@GetMapping("/handoffs/by-session")` | `public ApiResponse<List<HandoffRelationQueryResponse>> listHandoffsBySession(@RequestParam("sessionId") String sessionId)` |
| 179 | `@PostMapping("/user-preferences/upsert")` | `public ApiResponse<Void> upsertUserPreference(@RequestBody UserPreferenceRecord record, @RequestHeader(value = "Authorization", required = false) String authorization)` |
| 187 | `@GetMapping("/user-preferences/{userId}")` | `public ApiResponse<List<UserPreferenceRecord>> getUserPreferences( @PathVariable("userId") String userId, @RequestParam(value = "category", required = false) String category, @RequestHeader(value = "Authorization", required = false) String authorization)` |
| 196 | `@PostMapping("/user-preferences/{userId}/decay")` | `public ApiResponse<Void> decayUserPreferences( @PathVariable("userId") String userId, @RequestParam(value = "excludePreferenceKey", required = false) String excludePreferenceKey, @RequestHeader(value = "Authorization", required = false) String authorization)` |
| 208 | `@PostMapping("/system-constraints/upsert")` | `public ApiResponse<Void> upsertSystemConstraint(@RequestBody SystemConstraintRecord record, @RequestHeader(value = "Authorization", required = false) String authorization)` |
| 216 | `@GetMapping("/system-constraints")` | `public ApiResponse<List<SystemConstraintRecord>> getSystemConstraints( @RequestParam(value = "category", required = false) String category, @RequestHeader(value = "Authorization", required = false) String authorization)` |
| 224 | `@GetMapping("/system-constraints/search")` | `public ApiResponse<List<SystemConstraintRecord>> searchSystemConstraints( @RequestParam(value = "tags", required = false) String tags, @RequestHeader(value = "Authorization", required = false) String authorization)` |
| 232 | `@PostMapping("/rag/search")` | `public ApiResponse<MemoryRagSearchResponse> searchMemory(@RequestBody MemoryRagSearchRequest request, @RequestHeader(value = "Authorization", required = false) String authorization)` |
| 239 | `@PostMapping("/rag/backfill")` | `public ApiResponse<MemoryRagBackfillService.BackfillPage> backfillMemory( @RequestHeader(value = "Authorization", required = false) String authorization, @RequestParam(value = "cursor", required = false) String cursor, @RequestParam(value = "limit", defaultValue = "" + MemoryRagBackfillService.DEFAULT_LIMIT) int limit)` |
| 254 | `@PostMapping("/sessions/snapshot")` | `public ApiResponse<Void> saveSessionMemorySnapshot(@RequestBody SessionMemorySnapshotRequest request)` |
| 261 | `@GetMapping("/sessions/snapshot/{sessionId}")` | `public ApiResponse<SessionMemoryResponse> getLatestSessionMemorySnapshot(@PathVariable("sessionId") String sessionId)` |
| 269 | `@GetMapping("/workflow/{taskId}/history")` | `public ApiResponse<WorkflowHistoryView> getWorkflowHistory(@PathVariable("taskId") String taskId)` |
