# 官方 Graph 改造清单

## 1. 结论

当前项目已经完成了分布式多 Agent 的第一阶段主干：

- `A2A + AgentCard + AgentRegistry` 已落地
- `listing / marketing / media / trade / contract` 五个子 Agent 已接入
- `memory-service`、`artifact`、`handoff relation` 已具备最小闭环
- `RocketMQ` 事件总线已接入
- 审批、并行、handoff、checkpoint 已跑通

因此当前优先级已切换为：

1. 完成 Supervisor 主流程的官方 Spring AI Alibaba Graph 迁移
2. 补齐数据库 checkpoint 的旧格式迁移、多实例幂等和原生并行分支
3. 再继续补 `distributed-multi-agent-*.md` 中剩余业务功能

如果此时继续补 `settlement / notification / publish` 等业务点，本质上是在继续投资自定义 Graph 骨架，后续切官方 Graph 的迁移范围会被放大。

## 2. 依据

官方资料显示：

- Spring AI Alibaba Graph 是 Agent Framework 的底层运行时，负责持久化、工作流编排和流式能力
- Graph Core 的核心模型是 `StateGraph + OverAllState + AsyncNodeAction + ConditionalEdges + Checkpointer`
- 持久化和 human-in-the-loop 依赖 `Checkpointer + threadId`

参考：

- Spring AI Alibaba Graph Core 概念文档  
  https://java2ai.com/docs/frameworks/graph-core/core/core-library/
- Graph 持久化文档  
  https://java2ai.com/docs/frameworks/graph-core/examples/persistence/
- 官方仓库 README  
  https://github.com/alibaba/spring-ai-alibaba
- Maven artifact  
  https://mvnrepository.com/artifact/com.alibaba.cloud.ai/spring-ai-alibaba-graph-core

## 3. 当前实现与目标实现的差异

### 已有能力

- 官方 `StateGraph + OverAllState + ConditionalEdges` 已用于 Supervisor 顶层编排
- `SingleAgentSubgraph`、`ParallelAgentSubgraph` 和 `CompletionSubgraph` 已作为顶层 Graph 节点适配器
- 审批网关在 `WAITING_USER_APPROVAL` 后通过 `interruptAfter` 停止下游节点
- `ApprovalCallbackRequest` 通过 `updateState(...).withResume()` 恢复官方 Graph
- `DatabaseCheckpointSaver` 已接入现有 `agent_workflow_checkpoint` 表
- `SupervisorTaskService`、`SupervisorWorkflowService` 和新的异步提交路径已退化为 Graph 门面/任务调度

### 本轮已补齐的官方 Graph 能力

- 顶层 Graph 已使用 `addParallelConditionalEdges` 建立固定领域白名单的原生 fan-out，并通过统一 `PARALLEL_AGGREGATE` 节点执行 all-of fan-in；旧 `ParallelAgentSubgraph` 仅保留为兼容适配器
- `DatabaseCheckpointSaver` 可以识别原始 `SupervisorWorkflowState` 和没有 `graphName` 的旧 envelope，恢复时补齐官方状态并写入带 `migratedFromId` 的新 envelope
- 审批恢复增加 `agent_workflow_approval_claim` 唯一约束和数据库 claim store；相同 `approvalId` 在多实例竞争下只有一个实例获得 PROCESSING 权，完成回调重放已保存结果
- MCP/A2A 已完成最小真实容器 smoke：A2A Agent Card、JSON-RPC `/a2a`、MCP SSE `/sse` 和 session message endpoint 均已验证

### 仍需补齐的官方 Graph 能力

- 需要在包含真实旧 checkpoint 的环境执行一次完整的服务重启恢复演练，确认迁移后从待执行节点继续且不重复调用 Agent
- `any-of` 聚合策略还需要作为独立业务场景接入；当前 Supervisor 受保护业务默认使用 all-of
- Skill 匹配失败和非法 WorkflowPlan 的失败事件还需要专门的端到端回归覆盖

## 4. 改造原则

### 原则一

不推翻现有分布式协议层。

保留：

- `A2A DTO`
- `Approval DTO`
- `Artifact DTO`
- `SessionStreamEvent`
- `memory-service`
- `RocketMQ`
- `AgentRegistry`

### 原则二

Graph 只替换主 Agent 编排层，不重做子 Agent 业务逻辑。

### 原则三

先把现有“可工作的自定义骨架”迁移到官方 Graph 运行时，再继续新增业务链。

### 原则四

Graph checkpoint 和业务 memory 继续严格分层：

- Graph checkpoint：只存图执行状态
- `memory-service`：继续存 session memory、artifact、handoff relation

## 5. 清单

### P0-1 引入官方 Graph 依赖

目标：

- `agent-service` 接入 `spring-ai-alibaba-graph-core`
- 建立官方 Graph 状态模型和线程模型

改造项：

- `agent-service/pom.xml`
- 新增官方 Graph 状态类
- 新增 Graph 线程配置类

状态：已完成（Spring AI 1.1.2 与 Spring AI Alibaba 1.1.2.3 对齐）

### P0-2 建立官方 Graph 状态模型

目标：

- 用官方 `OverAllState` 承载 supervisor 图状态
- 明确 Graph state keys 与策略

改造项：

- 新增 `OfficialSupervisorGraphState`
- 新增 `OfficialSupervisorGraphKeys`
- 新增 `OfficialSupervisorGraphSchema`

状态：已完成

### P0-3 建立官方 Graph 编译入口

目标：

- 新增官方 `StateGraph` 编译入口
- 先跑通单链路：`LoadSession -> ParseIntent -> PlanTask -> SelectAgent -> Invoke -> Persist -> Finish`

改造项：

- 新增 `OfficialSupervisorGraphFactory`
- 新增 `OfficialCompiledSupervisorGraphHolder`

状态：已完成（顶层 Graph 已编译并由 Graph 门面持有）

### P0-4 节点适配

目标：

- 把现有节点适配为官方 Graph 节点

优先适配节点：

- `LoadSessionNode`
- `ParseIntentNode`
- `PlanTaskNode`
- `SelectAgentNode`
- `BuildInvokeRequestNode`
- `InvokeAgentNode`
- `PersistArtifactsNode`
- `PersistSessionNode`

状态：已完成（保留子图作为兼容适配器）

### P0-5 单 Agent 主链切换

目标：

- `SupervisorWorkflowService.startWorkflow` 单链路改走官方 Graph
- `SupervisorTaskService.submitTask` 单链路改走同一个官方 Graph 门面

状态：已完成（同步、工作流和异步本地任务统一进入 Graph）

### P0-6 审批子图切换

目标：

- `ApprovalSubgraphService` 迁移为官方可恢复子图
- 用 `threadId + checkpointer` 驱动暂停与恢复

状态：已完成第一版（审批网关、Graph interrupt、人工回调恢复已接入）

### P0-7 并行子图切换

目标：

- 并行调用迁移到官方 Graph 并行分支
- 汇聚后继续走 route decision

状态：已完成第一版（固定领域白名单的原生 fan-out/fan-in，默认 all-of，统一聚合节点负责缺失分支和失败处理）

### P0-8 Handoff 子图切换

目标：

- handoff 迁移为显式图节点流转
- 保留 `memory-service` handoff relation 落点

状态：进行中（保留既有 handoff 服务适配器，后续改成顶层显式节点）

### P0-9 Checkpointer 适配

目标：

- 以官方 Graph `Checkpointer` 接管当前 checkpoint 逻辑
- 当前自定义 `GraphCheckpointStore` 逐步退为兼容层或查询层

状态：已完成第一版（数据库 envelope + 每 Graph 独立 saver 实例 + 旧格式自动迁移）

### P1-1 Graph 化完成后再继续的功能

Graph 稳定后再继续：

- `settlement-agent-service`
- `notification-service` 纳入多 Agent 链路
- `marketing publish_prepare / publish` 完整闭环
- A2A async task/status 模式增强
- 更完整的权限、观测、限流、灰度

## 6. 类映射

### 现有类保留

- `AgentRegistry`
- `DynamicAgentRegistry`
- `A2aAgentClient`
- `MemoryStoreClient`
- `SessionStreamService`
- `TaskArtifactStore`
- `RouteDecisionNode`
- 子 Agent 控制器和服务

### 现有类过渡保留，后续降级

- `SupervisorGraphPlanner`
- `SingleAgentSubgraph`
- `ParallelAgentSubgraph`
- `ApprovalSubgraphService`
- `GraphCheckpointStore`

### 现有类重点改造

- `SupervisorWorkflowService`
- `SupervisorTaskService`

### 现有类未来应退出主编排职责

- `SupervisorWorkflowService` 中仍存在的手工流程拼接逻辑

## 7. 当前审批恢复协议

1. 入口请求由 `DefaultOfficialSupervisorGraphFacade` 生成或规范化 `sessionId`、`taskId`、`traceId`，并使用 `taskId` 作为 Graph `threadId`。
2. 顶层 Graph 根据受控计划进入 `SINGLE_AGENT`、`PARALLEL_FAN_OUT` 或 `APPROVAL_GATE`，不会接受请求或 LLM 直接注入节点名。
3. `APPROVAL_GATE` 创建 `ApprovalRequest`，写入待执行节点、批准/拒绝/终止候选动作、审批版本和重试信息，然后以 `WAITING_USER_APPROVAL` 状态中断。
4. 回调校验 `approvalId`、`taskId`、可选 `sessionId` 和 `approvalVersion`。批准、拒绝和终止分别由 Graph 条件边进入执行、重生成或取消节点。
5. 重复回调先由数据库唯一 `approvalId` claim 取得处理权；已完成回调重放数据库保存的 `SupervisorTaskResponse`，处理中回调不会再次调用下游 Agent。同一 JVM 内仍按 taskId 串行化恢复操作，作为减少本地竞争的优化而不是一致性保障。

## 8.1 最小运行与协议 smoke

当前最小可验证拓扑只启动 MySQL、Nacos、认证服务、网关和一个业务 Agent，不启动完整业务集群：

- listing Agent：`/.well-known/agent.json` 返回有效 Agent Card，`/a2a` 接受官方 A2A JSON-RPC；占位模型 Key 会在业务调用阶段返回 401，这是外部模型配置问题，不影响协议链路
- business Agent：`/actuator/health/readiness` 返回 `UP`，`GET /sse` 返回 MCP session endpoint，随后向 `/mcp/message?sessionId=...` 发送 `initialize` 得到 HTTP 200
- 当前 Nacos `3.0.3` 不支持 Spring AI Alibaba A2A Agent Card registry，服务启动日志会记录 `Request Nacos server version is too low`；直连 Agent Card 和 A2A endpoint 可用，生产环境需升级到支持 Agent registry 的 Nacos 版本后再验收 Nacos 发现
- Spring AI MCP SSE 默认入口是 `/sse`，消息入口是 `/mcp/message`；只有显式设置 `spring.ai.mcp.server.protocol=STREAMABLE` 时才使用 `/mcp`
- Spring AI Alibaba `2.0.0-M1.1` 不在本次迁移范围内，必须另开变更分支单独评估 Graph API、MCP/A2A starter、Nacos registry 和 checkpoint 兼容性

## 9. 第一批改造范围

本轮先做：

1. 引入官方 Graph 依赖
2. 新增官方 Graph 状态骨架
3. 新增官方 Graph 线程/配置骨架
4. 为后续编译入口准备统一门面

本轮不做：

- 一次性把所有流程切到官方 Graph
- 一次性替换审批、并行、handoff 全链路

## 10. 验收标准

当前已验证：

- `agent-service` 已引入官方 Graph 依赖并完成 Spring AI 依赖树检查
- 官方 Graph 状态模型、顶层 Graph 门面和数据库 checkpoint 适配器已入库
- 审批暂停/恢复最小 Graph 集成测试通过
- `mvn -pl agent-service -am test` 通过（agent-service 30 项；未配置真实 DB 时 opt-in 并发测试跳过，已单独使用 MySQL 复核通过）
- `mvn -pl agent-service -am -DskipTests compile` 通过
- `openspec validate migrate-supervisor-graph-approval-routing --strict` 通过
- 真实 MySQL 并发 claim 测试通过：两个连接竞争同一 `approvalId`，严格一成功一唯一键冲突
- 最小 listing/business 容器启动和 A2A/MCP 协议 smoke 已执行

后续验收：

- 旧 checkpoint envelope 自动迁移的真实重启恢复演练
- `any-of` 聚合策略的业务化接入
- SkillMatch/WorkflowPlan 非法输入的失败分支和端到端测试
- MCP/A2A smoke test 与带 Nacos/数据库的最小微服务启动

