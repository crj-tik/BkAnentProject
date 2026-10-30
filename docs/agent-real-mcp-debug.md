# Agent 工具接入与房源比较联调

## 当前调用边界

`agent-service` 的远程 MCP 连接由 `nacos/agent-service.yaml` 中的 `spring.ai.mcp.client.connections` 配置。当前连接为：

| MCP server | 服务 | Compose 地址 |
| --- | --- | --- |
| `marketing-mcp-server` | `marketing-content-service` | `http://marketing-content-service:9008/mcp` |
| `notification-mcp-server` | `notification-service` | `http://notification-service:9003/mcp` |
| `media-mcp-server` | `media-worker-service` | `http://media-worker-service:9007/mcp` |
| `interview-mcp-server` | `interview-service` | `http://interview-service:9014/mcp` |

平台经营、房源、客户等 Agent 能力通过官方 A2A Card 发现与调用。平台经营只发布 `platform-operations-agent`，分支能力是 analytics、review 和 knowledge；MCP server 默认禁用。房源比较属于 Listing 的 `listing-comparison` 能力，独立 Compare 服务与 MCP 已退役。

## 联调顺序

1. 核对 `nacos/agent-service.yaml` 中各连接地址、协议和超时；Compose 默认地址必须使用容器 DNS。
2. 确认目标服务启动并提供对应 Streamable HTTP MCP endpoint。
3. 调用 Agent 工具目录接口 `GET /agent/mcp/tools`，核对当前连接可见工具。
4. 分别检查目标 MCP 工具的授权与失败行为；A2A 服务检查 Agent Card、`/a2a` 地址、签名操作和 Memory 私域。
5. 房源比较通过 Listing Agent 和 `listing-comparison` 能力验证报告 owner、来源版本和 PDF 读取权限。

## 故障定位

- 工具列表缺项：先检查 Nacos 中连接名、服务 DNS、MCP 协议和目标服务是否启用 endpoint。
- Compose 容器互相连接失败：检查容器是否在共同网络、目标服务名和端口；容器内不要用宿主机 `127.0.0.1` 替代服务名。
- A2A 请求返回 401/403：检查 Gateway 登录令牌、Auth 签发的操作范围、调用者服务公钥和平台经营分支能力；前端不要构造内部签名头。
- 来源、数据库或真实 Provider 不可用：保留错误/空状态，不用示例结果冒充真实数据。
