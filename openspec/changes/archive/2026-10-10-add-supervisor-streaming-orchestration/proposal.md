## Why

Supervisor 当前能够通过 SessionStream/EventBus 推送工作流状态，但调用子 Agent 时主要使用阻塞式 A2A 响应或异步任务轮询，子 Agent 的 token、工具进度和 Agent 切换无法连续地汇聚到同一条客户端流中。需要先在 Supervisor 侧建立稳定的流式事件聚合、路由和断线恢复契约，之后再逐步改造各个 subagent；本变更不要求本阶段修改 subagent 实现。

## What Changes

- 建立 Supervisor 统一的流式事件模型，区分工作流事件、Agent 生命周期事件、输出增量、工具进度、handoff、审批、完成和失败事件。
- 增加 Supervisor 到子 Agent 的流式调用适配层：支持远程 A2A/本地子图事件转发，并在子 Agent 不支持流式时降级为状态事件加最终结果。
- 保持客户端只连接 Supervisor 的一条 SSE/流式通道；子 Agent 切换时沿用 `sessionId`、`taskId`、`traceId`，通过 `agentId`、`childRunId`、`phase` 和事件序号表达切换与并行关系。
- 明确 Graph 状态与流式事件的边界：最终结构化结果、artifact 引用和路由信息进入 checkpoint；token 增量和实时进度只作为可观测事件转发，不驱动 Graph 路由。
- 为流式事件增加可排序、可幂等、可断点续传的事件标识和回放语义，解决创建任务后订阅、客户端断线重连以及多实例 EventBus 场景下的事件丢失问题。
- 保持现有同步、异步、审批恢复、handoff 和并行接口兼容；本阶段只改 Supervisor 侧，不要求各个 subagent 立即实现新协议。

## Capabilities

### New Capabilities

- `supervisor-streaming-orchestration`: 定义 Supervisor 聚合子 Agent 流式事件、维持客户端单通道、执行 Agent 切换/并行/审批过渡、传递结构化上下文以及断线回放的行为。

### Modified Capabilities

<!-- No existing spec-level capability currently defines Supervisor streaming behavior. -->

## Impact

- 主要影响 `agent-service` 的 Supervisor Graph、A2A client/execution、SessionStream/EventBus、SSE controller 和异步工作流服务。
- 可能扩展 `common` 中的流式事件 DTO 以及数据库中的事件审计/序列字段；需要兼容现有 `SessionStreamEvent` 消费者和事件类型。
- 远程子 Agent 的当前同步调用、异步任务轮询和现有 A2A 请求/响应契约保留为 fallback；后续 subagent 改造可以逐个接入统一流式协议。
- 前端需要按 `sessionId` 订阅，并按照事件序号处理不同 `agentId` 的事件；不再根据 Agent 切换重新建立连接。
