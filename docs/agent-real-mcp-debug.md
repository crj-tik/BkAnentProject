# Agent 真实 MCP 联调说明

## 1. 当前架构

当前系统中：

- `business-service` 暴露真实 MCP Server：`http://host:9010/mcp`
- `compare-engine-service` 暴露真实 MCP Server：`http://host:9006/mcp`
- `marketing-content-service` 暴露真实 MCP Server：`http://host:9008/mcp`
- `agent-service` 通过 `agent.mcp.servers` 配置连接这些远端 MCP Server

## 2. Agent 侧配置

在 `nacos/agent-service.yaml` 中：

```yaml
agent:
  mcp:
    connect-timeout: 5s
    request-timeout: 15s
    initialization-timeout: 10s
    servers:
      business-mcp-server: http://127.0.0.1:9010/mcp
      compare-mcp-server: http://127.0.0.1:9006/mcp
      marketing-mcp-server: http://127.0.0.1:9008/mcp
```

## 3. 当前服务工具

### business-service

- server：`business-mcp-server`
- tool：`queryMonthlyKpis`

### compare-engine-service

- server：`compare-mcp-server`
- tool：`compareListings`

### marketing-content-service

- server：`marketing-mcp-server`
- tool：`publishMarketingContent`

## 4. 快速验证顺序

建议按下面顺序启动：

1. `compare-engine-service`
2. `business-service`
3. `marketing-content-service`
4. `agent-service`
5. 访问 `GET /agent/mcp/tools`

如果 MCP client 与 server 建连正常，返回结果中应能看到 3 个远端工具。

## 5. 常见排查项

### 5.1 `/agent/mcp/tools` 连接失败

优先检查：

- 目标服务端口是否已启动
- MCP endpoint 是否可达，例如 `http://127.0.0.1:9006/mcp`
- `agent.mcp.servers` 是否配置了正确 URL

### 5.2 服务启动正常但 MCP 不可用

检查服务内是否已注册：

- `HttpServletStreamableServerTransportProvider`
- `McpSyncServer`
- `ServletRegistrationBean(..., "/mcp", "/mcp/*")`

### 5.3 Planner 中工具调用失败

重点检查动作元数据：

- `executionType=MCP_CLIENT`
- `mcpServerName`
- `mcpToolName`

## 6. 当前本地环境限制

当前工作机上，本地完整联调可能受以下环境限制影响：

- Maven 本地仓库写权限问题
- 离线插件或依赖未完整缓存

常见现象：

- `AccessDeniedException: resolver-status.properties`
- 离线模式下 `surefire` 等插件依赖缺失

这类问题不影响代码编译通过，但会影响本机直接完整拉起全部服务链路。
