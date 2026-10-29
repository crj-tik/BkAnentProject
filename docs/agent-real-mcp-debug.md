# Agent MCP 与房源比较联调说明

## 当前调用边界

`agent-service` 的远程 MCP 连接由 `nacos/agent-service.yaml` 的 `spring.ai.mcp.client.connections` 配置。当前 MCP server 包括：

| MCP server | 服务 | 常用地址 |
| --- | --- | --- |
| `business-mcp-server` | `business-service` | `http://business-service:9010/mcp` |
| `marketing-mcp-server` | `marketing-content-service` | `http://marketing-content-service:9008/mcp` |
| `contract-mcp-server` | `contract-service` | `http://contract-service:9012/mcp` |
| `settlement-mcp-server` | `settlement-service` | `http://settlement-service:9013/mcp` |
| `notification-mcp-server` | `notification-service` | `http://notification-service:9003/mcp` |
| `media-mcp-server` | `media-worker-service` | `http://media-worker-service:9007/mcp` |
| `interview-mcp-server` | `interview-service` | `http://interview-service:9014/mcp` |

房源比较不再走独立 MCP server。Supervisor 通过 `a2a:listing-agent` 调用 Listing 的 `listing-comparison` 能力；Listing 负责来源版本核验、比较报告、owner 隔离和 PDF。

## 联调步骤

1. 在目标 Nacos namespace 核对 `agent-service.yaml` 中 `spring.ai.mcp.client.connections` 的连接名及 URL。
2. 确认目标服务已启动，并在服务模板中启用了 streamable HTTP MCP endpoint。
3. 调用 Agent 工具目录接口 `GET /agent/mcp/tools`，确认只返回当前配置中可用的工具。
4. 单独测试业务 MCP 工具；房源比较则通过 Agent 的 `listing-agent` A2A Card 与 listing-comparison 技能验证。

## 常见问题

- 工具列表缺服务：检查 Nacos 配置是否已加载、连接名是否拼写一致、服务名能否从 Agent 容器解析。
- MCP 连接失败：检查目标端口、`/mcp` 路径及服务端 streamable HTTP 配置；本机 `127.0.0.1` 不能代替 Compose 服务间地址。
- 房源比较不可用：检查 Listing Agent Card、A2A `/a2a` 地址、来源 provider 和当前授权上下文；不要排查或启动已退役的 Compare MCP。
- 数据源、模型或外部 Provider 未配置时，保持对应功能失败关闭，不用示例结果冒充线上数据。
