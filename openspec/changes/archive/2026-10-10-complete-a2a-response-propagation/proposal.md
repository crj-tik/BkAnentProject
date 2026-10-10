## Why

官方 A2A 迁移已经打通了 Supervisor 到 SubAgent 的调用和流式事件接收，但 SubAgent 的最终业务响应在返回 Supervisor 的过程中仍可能被降级为普通文本，且没有完整透传到公共 SSE。这样会导致 Supervisor 无法可靠获得结构化结果、远端任务与产物关联信息，也无法基于 `nextHints` 继续路由后续 SubAgent。现在需要补齐这条响应链路，才能使 A2A 调用真正闭环。

## What Changes

- 为现有官方 A2A 客户端补充统一的 Text、JSON/Data、Artifact 响应解析，将 SubAgent 输出映射为标准 `AgentTaskInvokeResponse`。
- 保留并传递远端 A2A `taskId`、artifact 标识、状态和错误信息，避免响应关联丢失。
- 将子 Agent 的最终结构化响应从执行层透传到 Supervisor 的会话流事件，使 SSE 客户端可以获得最终结果而不只看到文本增量。
- 让同步、流式和异步状态查询使用一致的响应归一化规则。
- 验证 Supervisor 能基于归一化响应继续执行上下文构建、产物持久化和下一 Agent 路由。
- 增加 A2A 响应适配、终态 SSE 透传和 Supervisor 路由的自动化测试。

## Capabilities

### New Capabilities

- `supervisor-a2a-response-propagation`: 定义官方 A2A 子 Agent 的结构化响应、任务/产物关联、流式终态透传以及 Supervisor 后续路由行为。

### Modified Capabilities

无。

## Impact

- 影响 `agent-service` 中的 `OfficialA2aAgentClient`、A2A 执行服务、子 Agent 流事件和会话 SSE 事件模型。
- 影响 Supervisor Graph 对 `AgentTaskInvokeResponse` 的消费、上下文构建、产物持久化和 handoff 路由。
- 影响 agent-service 的单元测试和流式集成测试；不改变 A2A 外部协议，不要求各 SubAgent 更换传输方式。
- 可能需要统一各 SubAgent 返回的结构化 JSON 字段约定，以便 Supervisor 稳定解析。
