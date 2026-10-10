## Why

Supervisor 侧已经具备官方 A2A Message、Task、Artifact 的响应归一化能力，但当前 SubAgent 仍由 Starter 默认 `GraphAgentExecutor` 驱动：它只读取 `TextPart`，并把最终结果固定写成文本 Artifact，导致 Agent Card 虽然声明支持 `application/json`，实际却无法稳定返回结构化结果。现在需要补齐服务端输出契约，才能让 Supervisor 的归一化、路由和 SSE 终态透传真正获得可靠输入。

## What Changes

- 为所有官方 A2A SubAgent 建立统一的请求输入和响应输出契约，支持 Text/Data 输入以及 Supervisor 上下文、关联字段和结构化业务上下文。
- 用项目侧官方 A2A Executor 适配器替换 Starter 默认 Executor，统一生成 Text/Data Part、终态 Artifact、稳定 Artifact ID 和任务状态。
- 规定成功终态返回顶层结构化 JSON 对象，至少包含 `contentType`、`summary`、`nextHints`；领域业务字段保持在同一对象中，便于 Supervisor 直接路由和持久化。
- 规定流式调用先发送可展示的进度/文本增量，结束时只补发一次完整结构化终态；失败、取消和非法结构化输出必须映射为明确的非成功 A2A Task 状态。
- 让 8 个领域官方 Provider 共用相同的 A2A 适配逻辑，并校准 Agent Card 的输入/输出模式、流式能力和领域技能声明。
- 为输入解析、结构化输出、Artifact、流式终态、错误状态、上下文关联和 8 个 Provider 配置增加自动化验证。

## Capabilities

### New Capabilities

- `subagent-a2a-output-contract`: 定义 SubAgent 官方 A2A 的输入解析、结构化输出、Artifact、流式终态、状态错误和 Supervisor 上下文关联行为。

### Modified Capabilities

无。

## Impact

- 影响 `business-service`、`listing-master-service`、`marketing-content-service`、`media-worker-service`、`contract-service`、`settlement-service`、`notification-service` 和 `compare-engine-service` 的官方 A2A Provider、服务端 Executor 配置及测试。
- 影响 `nacos/*-service.yaml` 中官方 Agent Card 的模式和能力声明；保持现有 `/a2a` 与 `/.well-known/agent.json` 地址不变。
- 需要在项目公共代码或独立公共 A2A 支持模块中提供可复用的协议适配器，并继续使用现有 Alibaba A2A SDK、Spring AI Alibaba ReactAgent 和 Supervisor 侧响应归一化器。
- 不改变 Supervisor 的 A2A 客户端、LangGraph 路由或公共 SSE 事件协议；旧客户端仍可读取文本增量，新客户端可以读取结构化 Data Artifact。
