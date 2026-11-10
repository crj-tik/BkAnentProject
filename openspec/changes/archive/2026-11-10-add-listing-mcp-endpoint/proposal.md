## Why

房源域当前对 Agent 生态只暴露 A2A 通道（listing-agent），而 agent-service 的 MCP 客户端目录已接入 marketing/notification/media/interview 四个 MCP 服务，唯独没有房源——MCP 客户端无法消费房源搜索、详情、版本化来源与对比能力。仓库已有四个完全一致的 MCP 实现先例，补齐这一通道成本低、模式成熟。

## What Changes

- 在 `listing-master-service` 上新增 MCP 端点（`http://listing-master-service:9004/mcp`，STREAMABLE），复用仓库既有模式：`spring-ai-starter-mcp-server-webmvc` + `McpTool` 标记接口 + `McpAuthorizationFilter` 逐工具鉴权。
- 新增 `ListingMcpTools implements McpTool`，暴露 6 个只读/计算工具：摘要搜索、按 ID 摘要、完整详情、版本化来源搜索、创建对比（含卡片投影）、读取对比报告——全部包装现有服务层，不重复实现业务逻辑。
- **不暴露写操作**（登记/维护/状态/素材/业主/OCR/核验）——这些是经纪人身份的写操作，走 REST + 网关签名上下文；MCP 调用方是 agent-service（代理用户），放入 MCP 会绕过经纪人归属模型。
- 新增 `ListingMcpConfiguration`（mcpToolCallbackProvider Bean，鉴权开关门控）；application.yml 与 Nacos 增加 `mcp.authorization.server` 段（caller=agent-service、公钥、tool-operations、public-tools），默认关闭。
- agent-service 的 MCP 连接目录（nacos/agent-service.yaml）新增 `listing-mcp-server`。
- 与 A2A（listing-agent）双通道并存，marketing 域是先例；两通道调用同一服务层。

## Capabilities

### New Capabilities

- `listing-mcp-endpoint`: 房源域 MCP 通道——只读工具集、逐工具鉴权、与 A2A 并存不重复。

### Modified Capabilities

无。不修改已归档主规范中的任何需求；A2A 通道与 REST 契约不变。

## Impact

涉及 `listing-master-service`（pom 依赖、McpTools、Configuration、application.yml）、`nacos/listing-master-service.yaml`（mcp 授权段）、`nacos/agent-service.yaml`（MCP 连接目录）、`.env.example`/`docker-compose.yml`（鉴权开关与公钥接线）。无数据库迁移、无新 Auth 操作码（复用 `listing.read`、`compare.report.create`、`compare.report.read.own`）、无网关改动（MCP 直连不走网关）。测试：装配测试（工具可发现、开关门控）+ 工具鉴权正反例。
