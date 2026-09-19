# Supervisor Alibaba 官方 A2A 运行时迁移说明

## 运行时边界

Supervisor 子 Agent 只通过 Alibaba Cloud 官方 A2A 发现和调用。注册信息必须能够解析官方 Agent Card：

- Agent Card 路径为 `/.well-known/agent.json`；
- Agent Card 必须提供官方 A2A endpoint；
- Nacos 元数据中的 `agent-runtime-provider` 必须为 `official`；
- 同步、流式和异步执行都使用官方 Message、Task、Status、Stream、Artifact 操作。

Supervisor 内部仍然使用 `AgentTaskInvokeRequest`、`AgentTaskInvokeResponse` 和异步任务状态对象进行图路由、交接、持久化和重试。这些对象是内部规范化模型，不是 HTTP 请求体或响应体。官方 A2A 适配器会把调用指令放入 Message 文本，把 Supervisor 上下文放入 `supervisor` metadata 命名空间，并保留 A2A `threadId`、`isStreaming` 等运行时字段。

每个领域 SubAgent 的官方 `ReactAgent` 都注册了 Supervisor 上下文拦截器。拦截器从官方 A2A metadata 读取 `threadId`、`isStreaming` 以及 `supervisor` 内的 session/task/trace、结构化上下文、约束和期望输出，并把它们注入模型系统上下文；上下文仅用于本次 Agent 执行，不会改变 MCP 工具协议，也不会把内部关联 ID 输出给用户。

## 注册失败处理

以下注册不会进入路由表：

1. `agent-runtime-provider: custom` 或其他未知 provider。错误信息会指出必须使用 Alibaba 官方 A2A。
2. 缺少 Agent Card、使用非 `/.well-known/agent.json` 的自定义卡片路径，或 Agent Card 无官方 endpoint。
3. 官方 Agent Card 无法通过 Nacos 或远程 Agent Card provider 解析。
4. 请求的流式能力未在 Agent Card 中声明。系统返回能力错误或按既定逻辑退回非流式调用，不会调用自定义流式路径。

## 配置示例

```yaml
metadata:
  agent-id: listing-agent
  agent-runtime-provider: official
  agent-card-path: /.well-known/agent.json
  a2a-path: /a2a
```

不再配置 `agent-official-payload-mode`、`a2a-task-create-path`、`a2a-task-status-path` 或 `a2a-task-stream-path`。任务创建、查询和流式连接由官方 A2A 客户端依据 Agent Card 能力处理。

## 外部 Agent 迁移

仅暴露旧自定义 HTTP 接口的 Agent 必须先增加 Alibaba 官方 Agent Card 和 A2A endpoint，再加入 Nacos 注册。发布前应完成 Agent Card、同步消息、流式事件、异步任务轮询、Artifact、协议错误和失败任务的契约验证；否则该 Agent 会被拒绝注册。

## MCP 与 SubAgent 工具调用边界

A2A 和 MCP 负责不同的边界：

- Supervisor 到 SubAgent 使用 Alibaba 官方 A2A。SubAgent 收到 A2A Message 后，由自己的 ReactAgent 继续处理任务。
- Supervisor 自己需要调用领域工具时，使用 Spring AI MCP Client 发现工具并通过 MCP `tools/call` 执行。静态 MCP 连接由 `spring.ai.mcp.client.connections` 配置，动态连接由 `DynamicMcpClientManager` 管理。
- SubAgent 内部当前使用本服务注册的本地 `ToolCallback` 执行领域方法。这不会把 A2A 请求自动转换成 MCP 请求，也不会与官方 A2A 冲突；MCP 服务端是给 Supervisor 或其他 MCP Client 的独立工具入口。

领域服务的 MCP Server 统一使用 Streamable HTTP，endpoint 为 `/mcp`，与 `agent-service.yaml` 中的 MCP Client 连接保持一致。此前部分服务默认使用 SSE，而 Supervisor 仅创建 Streamable HTTP 客户端，按默认配置会导致工具发现和调用失败。

如果后续要求 SubAgent 的每一次内部工具执行也必须经过 MCP，需要为该 SubAgent 增加 MCP Client 连接和远程工具回调，并移除或明确禁用本地回调；这属于工具执行架构改造，不是 A2A 协议适配的一部分。
