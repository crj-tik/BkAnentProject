## Context

See `proposal.md` for the motivation and scope. 当前 `agent-service` 已经使用 Spring AI Alibaba Graph 编译 Planning、Single Agent、Parallel、Approval、Resume 和 Completion 等局部图，但主流程仍由 `SupervisorWorkflowService`、`SupervisorTaskService` 以及多个自定义 Subgraph 通过 Service 逻辑拼接。官方 Graph 版本 `1.1.2.3` 已具备条件边、并行条件边、状态历史和 Checkpoint 能力；项目当前主要使用线性 `addEdge`、`invoke` 和独立的 `MemorySaver`。

本设计保持 Java 17、Spring Boot 3.5.x、现有 A2A DTO、审批回调 DTO、Artifact、事件审计和数据库表结构的兼容性，先在稳定的 Spring AI Alibaba 1.1 线上完成统一，再单独评估 2.0 里程碑版本。

## Goals / Non-Goals

**Goals:**

- 让 Supervisor Graph 成为主工作流的唯一状态转换入口。
- 将审批等待建模为 Graph 的可恢复中断状态，人工决策通过条件路由选择后续节点。
- 统一单 Agent、并行 Agent、审批、重试、handoff、完成和失败分支的状态模型。
- 保留现有对外 DTO、A2A 协议、事件审计和查询接口。
- 让 Graph 状态能够使用数据库持久化并支持服务重启后的恢复。
- 使用官方条件边和并行分支能力，减少 Service 中的流程判断。

**Non-Goals:**

- 本变更不重新设计子 Agent 的业务能力、A2A 协议或各领域工具。
- 本变更不允许 LLM 直接生成任意可执行 Graph DSL；动态计划仍必须经过白名单和转换策略校验。
- 本变更不在同一批次升级到 Spring Boot 4 或 Spring AI Alibaba 2.0.0-M1.1。
- 本变更不把长期业务记忆、用户偏好和知识库数据混入 Graph Checkpoint；这些数据继续由现有 memory/artifact 能力管理。

## Decisions

### 1. 使用一个规范化 Supervisor Graph 作为主编排入口

新增统一的 Graph 门面和顶层 Graph Factory，将入口请求转换为初始 Graph state，并由 Graph 完成：

```text
LoadSession -> SkillMatch -> Plan -> Validate -> Route
  -> SingleAgent | ParallelAgents
  -> ApprovalGate
  -> ResumeDecision
  -> Handoff | Regenerate | Complete | Cancel | Fail
```

`SupervisorTaskService` 和 `SupervisorWorkflowService` 退化为入口适配和查询协调层，不再根据 `requireApproval`、`parallelDomains`、`resumeAction` 或 Agent 响应手工选择主流程节点。保留现有子图类作为 Graph 节点实现或兼容适配层，避免一次性重写子 Agent 调用逻辑。

备选方案是继续让两个 Service 各自调用局部子图，改动较小但会继续产生两套工作流语义，因此不采用。

### 2. 审批使用显式 Graph 状态和条件恢复

审批网关节点只负责判断是否需要审批和创建审批请求；需要审批时将状态写入 `WAITING_USER_APPROVAL`，并保存：

- approvalId、taskId、sessionId、threadId、traceId
- 当前节点和批准后的候选下一节点
- 审批动作允许值
- retryCount、maxRetryCount
- reviewer feedback 和计划版本

审批回调先做幂等和关联校验，再把决策作为恢复输入交给 Graph。决策路由表由系统维护，允许值至少包括 `complete`、`regenerate`、`invoke-next` 和 `cancel`；未知动作拒绝恢复。这样可以保证人工审批只改变受控状态，不可以注入任意节点名称。

备选方案是保留当前 `handleCallback` 中的 `switch` 并调用不同 Subgraph。该方案无法让 Graph 保存和恢复真实执行位置，因此只作为迁移兼容路径，不作为最终主路径。

### 3. 使用条件边和官方并行分支

规划节点输出经过校验的结构化 `WorkflowPlan`，Graph 只根据系统允许的路由模板构建或选择分支：

- 单 Agent：单一执行节点后进入 artifact/session 持久化和完成。
- 并行：按允许的 `parallelDomains` 建立分支，使用统一聚合节点收集结果。
- 审批：在配置的受保护节点前进入审批网关。
- handoff：只有计划和注册表均允许的目标才能进入 handoff。

LLM 只能提出计划参数和步骤，不能直接决定任意 Graph 节点或服务地址。`WorkflowPlanValidator` 增强为校验步骤数量、步骤类型、领域、Agent 能力、审批插入点、并行上限和允许转换。

### 4. 统一 Graph state 与业务 checkpoint 的边界

Graph 的规范状态以官方 `OverAllState` 的键集合为准，`SupervisorGraphState` 和 `SupervisorWorkflowState` 只在 Graph 门面、兼容节点和查询层进行适配。状态键分为：

- replace：请求、计划、当前节点、当前 Agent、审批决策、工作流状态和错误。
- append：事件引用、artifact 增量、handoff 历史；节点只能写入增量，不能把累积列表再次整体追加。

实现一个基于现有数据库 checkpoint 表的官方 Checkpointer 适配层，并统一 `threadId = taskId`、`sessionId` 作为业务关联字段。数据库 checkpoint 是恢复的事实来源，memory saver 只允许作为测试替身。

备选方案是继续维护 `MemorySaver + GraphCheckpointStore` 双写。该方案在单实例测试中可用，但服务重启、重复回调和多实例情况下会出现状态漂移，因此不采用。

### 5. 依赖先对齐稳定版本，再评估 2.0

本变更将 Spring AI 版本对齐到 Spring AI Alibaba 1.1.2.3 所对应的稳定 Spring AI 1.1.2，并在此基础上验证 Graph、MCP、A2A 和 DashScope。2.0.0-M1.1 需要 Spring AI 2.0.0-M1 和 Spring Boot 4.0.0，作为独立升级分支评估，不与本次主流程迁移混合。

### 6. 通过 Graph 事件统一可观测性

每个关键节点由统一审计适配器产生开始、成功、失败和等待事件。事件携带 Graph node、workflow status、threadId、taskId、traceId 和当前 attempt，现有 SessionStream 和 event-audit 继续作为传输与查询出口。

## Risks / Trade-offs

- [Risk] 旧的 `/supervisor/tasks` 简化路径切换到完整 Graph 后，返回状态和事件数量可能增加 → 保留 DTO 字段兼容，增加回归测试并在文档中说明统一语义。
- [Risk] 数据库 Checkpointer 与现有 `GraphCheckpointStore` 的序列化格式不完全一致 → 先定义版本化状态 envelope，提供旧 checkpoint 读取适配和迁移测试。
- [Risk] 审批恢复重复提交导致 Agent 重复执行 → 使用 approvalId/taskId/threadId 唯一约束和幂等结果缓存，恢复前后都做状态版本校验。
- [Risk] 并行分支中任一节点失败会影响聚合 → 明确 all-of/any-of 策略，默认采用可配置的 all-of，并为部分失败结果保留诊断信息。
- [Risk] Spring AI 版本对齐可能暴露 MCP 或模型适配兼容问题 → 先锁定稳定版本并运行完整模块测试、最小启动和 MCP/A2A smoke test，再提交版本调整。
- [Risk] Graph 节点过度承载业务逻辑会形成难以测试的大节点 → 节点只负责状态转换和调用领域 Service，领域 Service 不得反向控制 Graph 主流程。
