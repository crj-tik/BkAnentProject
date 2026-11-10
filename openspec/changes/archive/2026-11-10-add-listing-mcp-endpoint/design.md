## Context

仓库已有四个 MCP 服务（marketing/notification/media/interview）使用完全统一的实现模式：`spring-ai-starter-mcp-server-webmvc`（STREAMABLE `/mcp` 端点）+ `McpTool` 标记接口（common）+ `McpAuthorizationFilter` 逐工具鉴权（`mcp.authorization.server.*`：caller=agent-service、Auth 公钥、tool-operations 工具名→操作码、public-tools 只读白名单）+ `XxxMcpConfiguration` 的 `mcpToolCallbackProvider` Bean。listing-master-service 当前无该依赖，端口 9004，`/mcp` 路径无冲突；其 A2A 通道（listing-agent + ListingTools）与 REST 契约完整。动机与范围见 proposal；行为见 specs/listing-mcp-endpoint。

## Goals / Non-Goals

**Goals:** 以最小改动照抄既有模式，让 agent-service 的 MCP 客户端目录及标准 MCP 客户端可消费房源只读与对比能力；鉴权开关默认关闭、接线后开启；三通道（REST/A2A/MCP）共用服务层。

**Non-Goals:** 不暴露房源写操作（登记/维护/状态/素材/业主/OCR/核验）；不新建独立 MCP 服务进程；不修改 A2A 技能、REST 契约、Auth 协议或网关；不新增数据库迁移或 Auth 操作码（复用 `listing.read`、`compare.report.create`、`compare.report.read.own`）。

## Decisions

### 1. 端点放在现有 listing-master-service，而非独立进程

复用 9004 端口加 `/mcp` 路径（spring-ai-starter-mcp-server-webmvc 默认），不新建服务/容器/Compose 项。对比独立 listing-mcp-server 进程：避免重复的服务层装配与鉴权接线；marketing/notification/media/interview 全部是"现有服务内嵌 MCP 端点"模式，无独立进程先例。MCP 客户端由 agent-service 直连服务（不走网关），与既有四个服务一致，无网关改动。

### 2. 工具集：6 个只读/计算工具，包装现有服务层

`ListingMcpTools implements McpTool`，方法带 `@Tool/@ToolParam`（Spring AI 注解），内部调用 `ListingManagementService`（摘要/详情/来源搜索）与 `ListingComparisonService`（对比创建/报告读取），方法内 `VerifiedDomainAuthorization.requireOperation(...)`（与 MarketingMcpTools 一致）：

| 工具 | 服务层 | 操作码 | public-tools |
|---|---|---|---|
| search_listing_summaries | searchListingSummaries | listing.read | 是 |
| get_listing_summary | getListingSummary | listing.read | 是 |
| get_listing_detail | getListingDetail | listing.read | 否 |
| search_listing_source | 业务来源搜索（版本化引用） | listing.read | 否 |
| create_listing_comparison | comparison.create（含卡片投影） | compare.report.create | 否 |
| get_comparison_report | comparison.getOwned（含卡片） | compare.report.read.own | 否 |

写操作不进 MCP：MCP 调用方是 agent-service 代理用户，写操作必须走经纪人身份的 REST + 网关签名上下文（决策依据：经纪人归属模型，LR-176/177 系列）。工具类与 A2A 的 ListingTools 分离（marketing/media 先例），两者调同一服务层。

### 3. 鉴权：完全复用 common 的 McpAuthorization 链

`mcp.authorization.server` 段（application.yml + nacos/listing-master-service.yaml）：`enabled`（默认 false，env `LISTING_MCP_AUTHORIZATION_ENABLED`）、`caller-service: agent-service`、`authority-public-keys`（Auth 公钥）、`caller-public-keys`（Agent 公钥）、`tool-operations`（上表映射）、`public-tools`（两个摘要工具）。`ListingMcpConfiguration` 的 `mcpToolCallbackProvider` Bean 以 `@ConditionalOnProperty(mcp.authorization.server.enabled)` 门控（InterviewMcpConfiguration 模式）——开关关闭时工具不注册。Compose 与 `.env.example` 同步开关与公钥接线（KI-242 教训：单侧开启会互相拒绝）。

### 4. agent-service 侧登记

`nacos/agent-service.yaml` 的 `spring.ai.mcp.client.connections` 新增 `listing-mcp-server: url: ${LISTING_MCP_ENDPOINT:http://listing-master-service:9004/mcp}, type: STREAMABLE`。动态能力目录不需要新映射（MCP 工具按工具名消费，不走 a2a: 映射）。

## Risks / Trade-offs

- [工具与 A2A 能力重复] → 有意为之的双通道（marketing 先例）；两者调同一服务层，行为漂移由服务层测试约束。
- [鉴权开关默认关导致"上线即不可用"] → 与其他四个 MCP 服务一致的显式门控；公钥与开关接线列入任务并在部署清单核对。
- [spring-ai-starter-mcp-server-webmvc 引入与现有 A2A 依赖的冲突] → 版本由根 pom spring-ai-bom 统一管理；装配测试验证启动无冲突。
- [MCP 工具返回大对象（对比报告含 cards）] → 工具描述明确返回结构；不做截断，由调用方（模型）自行取舍。

## Migration Plan

1. 核对四个既有 MCP 服务的配置矩阵（开关、公钥、tool-operations、public-tools、agent 连接目录）作为模板。
2. 实现 pom 依赖、ListingMcpTools、ListingMcpConfiguration、application.yml/Nacos 段、agent-service 连接目录、Compose/.env.example 接线。
3. 装配测试：开关关闭时工具不注册；开启后 6 工具可发现；鉴权正反例（public 工具免操作码、受限工具缺操作码拒绝）；listing 模块全量回归。
4. 部署阶段：公钥接线后开启开关，agent-service 经 MCP 目录实际调用验证。
