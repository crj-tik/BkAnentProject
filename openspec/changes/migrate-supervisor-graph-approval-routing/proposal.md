## Why

当前 Supervisor 已经使用 Spring AI Alibaba Graph 执行规划和部分子图，但审批、恢复、并行后的路由、handoff 和完成判断仍由 `SupervisorWorkflowService` 通过手工条件分支控制。这样会让 Graph 状态与业务 Service 的状态逐渐分叉，人工审批无法成为 Graph 的正式中断点，也难以保证服务重启、多实例和后续动态工作流的一致性。

现在需要把“是否进入审批、审批后的下一节点、审批选项对应的动作”提升为官方 Graph 的状态转换和条件边，让 Service 只负责入口鉴权、运行门面和查询，不再负责主流程编排。

## What Changes

- 建立统一的 Supervisor 官方 Graph，覆盖规划、条件路由、单 Agent、并行 Agent、审批等待、人工决策恢复、handoff、持久化和完成节点。
- 将审批作为 Graph 的可恢复中断状态；进入审批后停止后续业务节点，只有收到合法人工决策后才继续执行。
- 根据 `APPROVED`、`REJECTED`、`TERMINATED` 以及审批动作选项，将流程分别路由到完成、重新生成、下一 Agent、取消或失败节点。
- 用官方 Graph 条件边和并行分支替代 `SupervisorWorkflowService` 中对并行、审批、路由、handoff 和完成状态的主流程 `if/else` 判断。
- 将 `/supervisor/tasks` 和 `/supervisor/workflows` 收敛到同一个 Supervisor Graph 门面，保留现有请求和响应 DTO 的兼容性。
- 接入 `SkillMatchNode`，使技能知识在 LLM 规划前正式进入 Graph 状态。
- 为 Graph 状态补齐请求流式标记、计划、审批动作、错误、重试和恢复信息，并明确状态键的替换与追加策略。
- 将官方 Graph Checkpointer 与现有数据库 checkpoint 统一，确保使用 `threadId` 的 Graph 状态可以跨重启恢复。
- 将 Spring AI 依赖对齐到 Spring AI Alibaba `1.1.2.3` 所对应的稳定 Spring AI 版本，单独保留后续评估 `2.0.0-M1.1` 的升级空间。
- **BREAKING**：服务内部不再允许通过绕过 Supervisor Graph 的方式直接拼接主工作流；子 Agent 的 A2A 协议和对外请求/响应模型保持兼容。

## Capabilities

### New Capabilities

- `supervisor-graph-orchestration`: 统一描述 Supervisor Graph 的条件路由、审批中断与恢复、并行执行、handoff、完成和持久化行为。

### Modified Capabilities

无。现有 OpenSpec 能力中没有覆盖 Supervisor Graph 的行为要求，本次新增独立能力规范。

## Impact

- 主要影响 `agent-service` 的 `graph/official`、`graph/node`、`SupervisorWorkflowService`、`SupervisorTaskService` 和 Graph 状态适配代码。
- 需要新增或改造官方 Graph Checkpointer、线程标识解析和数据库状态映射。
- 需要补充 Graph 工厂、条件边、审批恢复、重启恢复、并行聚合和服务门面测试。
- 需要调整根 `pom.xml` 的 Spring AI 版本对齐配置，并重新验证 MCP、A2A、DashScope 和 Graph 运行时。
- 不改变子 Agent 的业务接口、A2A DTO、审批回调 DTO 和已有查询接口的基本契约。
