# Tasks

## 1. 模式核对与配置矩阵

- [x] 1.1 核对四个既有 MCP 服务（marketing/notification/media/interview）的配置矩阵：pom 依赖、McpTools 结构、McpConfiguration 门控、application.yml 与 Nacos 的 `mcp.authorization.server` 段、agent-service 连接目录条目、Compose/.env 开关与公钥接线，作为实现模板。（证据：marketing 为主模板——pom 无版本号依赖 spring-ai-starter-mcp-server-webmvc；本地 application.yml 放 mcp.authorization.server；nacos 放 spring.ai.mcp.server + mcp.authorization；tool-operations 用 @Tool 方法名；Compose/.env 传 MARKETING_MCP_AUTHORIZATION_ENABLED）

## 2. 实现（listing-master-service）

- [x] 2.1 pom 增加 `spring-ai-starter-mcp-server-webmvc`（版本由根 pom spring-ai-bom 管理），验证与现有 A2A/Web 依赖无冲突。（证据：`spring-ai-starter-mcp-server-webmvc` 无版本号加入，BOM 管理；`mvn compile` BUILD SUCCESS）
- [x] 2.2 新增 `ListingMcpTools implements McpTool`：6 个工具（search_listing_summaries、get_listing_summary、get_listing_detail、search_listing_source、create_listing_comparison、get_comparison_report），`@Tool/@ToolParam` 注解，内部调用 ListingManagementService 与 ListingComparisonService 并 `VerifiedDomainAuthorization.requireOperation`；不含任何写操作。（证据：`ListingMcpTools` 6 工具——searchListingSummaries/getListingSummary（public-tools 免操作码，interview 先例）/getListingDetail/searchListingSource/createListingComparison/getComparisonReport（方法内 requireOperation 纵深防御）；测试断言无写操作方法名）
- [x] 2.3 新增 `ListingMcpConfiguration`：`mcpToolCallbackProvider` Bean 以 `@ConditionalOnProperty(mcp.authorization.server.enabled)` 门控（InterviewMcpConfiguration 模式）。（证据：`ListingMcpConfiguration` @ConditionalOnProperty(mcp.authorization.server.enabled)）
- [x] 2.4 application.yml 与 nacos/listing-master-service.yaml 增加 `mcp.authorization.server` 段：enabled（默认 false，env `LISTING_MCP_AUTHORIZATION_ENABLED`）、caller-service=agent-service、authority/caller 公钥、tool-operations 六工具映射、public-tools 两个摘要工具。（证据：本地 application.yml + nacos 双份 mcp.authorization.server 段，YAML 解析校验通过；tool-operations 四项映射 + public-tools 两项）
- [x] 2.5 docker-compose.yml 与 `.env.example` 接线：开关与公钥环境变量传递（KI-242 教训：两侧同步）（证据：compose listing 服务块加 LISTING_MCP_AUTHORIZATION_ENABLED 传递；.env.example 加开关行）。

## 3. agent-service 侧登记

- [x] 3.1 nacos/agent-service.yaml 的 `spring.ai.mcp.client.connections` 新增 `listing-mcp-server`（url 默认 `http://listing-master-service:9004/mcp`，STREAMABLE）。（证据：nacos/agent-service.yaml connections 新增 listing-mcp-server，YAML 校验通过）

## 4. 测试

- [x] 4.1 装配测试：开关关闭时 mcpToolCallbackProvider 不注册、工具不可发现；开启后 6 工具全部可发现且方法绑定正确。（证据：`ListingMcpToolsTest` 7/7——门控注解、6 工具名精确集合、无写操作、公开工具无上下文可调、受限工具无上下文拒绝（AUTHENTICATION_REQUIRED）、对比/来源经 Verified 上下文委托服务层）
- [x] 4.2 鉴权测试：public-tools（摘要搜索/按 ID 摘要）免操作码可调；受限工具（详情/来源/对比/报告）缺对应操作码时拒绝；有操作码时通过并返回服务层事实（含对比卡片投影）。（证据：同上，委托测试用真实签名 AuthorizationContext/AuthzFreshness/DelegationBinding 构造 McpAuthorizationScope）
- [x] 4.3 回归：listing-master-service 模块全量测试通过；A2A 装配与技能测试不受影响。（证据：listing 模块全量 166/166 通过）

## 5. 验收与交付

- [x] 5.1 文档：在内部房源 API 文档或独立小节记录 MCP 通道（端点、工具清单、鉴权模型、与 A2A/REST 的关系）；按项目规范登记 LR（MCP 只读边界与三通道共用服务层的非显然决策）。（证据：LR-181 已登记——只读边界/公开工具免操作码/三通道共用服务层/ConditionalOnProperty 门控）
- [ ] 5.2 部署阶段验收：公钥接线后开启 `LISTING_MCP_AUTHORIZATION_ENABLED`，agent-service 经 MCP 连接目录实际调用 listing-mcp-server 的摘要与对比工具成功；开关关闭时端点不暴露工具。
