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

### 仍需补齐的官方 Graph 能力

- 目前并行 Agent 仍由现有聚合适配器调用，尚未迁移为 Graph 原生 fan-out/fan-in 分支
- 旧 `GraphCheckpointStore` 行格式目前只保留兼容查询，尚未自动迁移为官方 envelope
- 多实例审批回调还需要数据库级 claim/version 条件更新；当前代码提供同 JVM 锁和 checkpoint 幂等键
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

状态：进行中（当前仍由并行适配器聚合，原生 fan-out/fan-in 待后续变更）

### P0-8 Handoff 子图切换

目标：

- handoff 迁移为显式图节点流转
- 保留 `memory-service` handoff relation 落点

状态：进行中（保留既有 handoff 服务适配器，后续改成顶层显式节点）

### P0-9 Checkpointer 适配

目标：

- 以官方 Graph `Checkpointer` 接管当前 checkpoint 逻辑
- 当前自定义 `GraphCheckpointStore` 逐步退为兼容层或查询层

状态：已完成第一版（数据库 envelope + 每 Graph 独立 saver 实例）

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
2. 顶层 Graph 根据受控计划进入 `SINGLE_AGENT`、`PARALLEL_AGENTS` 或 `APPROVAL_GATE`，不会接受请求或 LLM 直接注入节点名。
3. `APPROVAL_GATE` 创建 `ApprovalRequest`，写入待执行节点、批准/拒绝/终止候选动作、审批版本和重试信息，然后以 `WAITING_USER_APPROVAL` 状态中断。
4. 回调校验 `approvalId`、`taskId`、可选 `sessionId` 和 `approvalVersion`。批准、拒绝和终止分别由 Graph 条件边进入执行、重生成或取消节点。
5. 重复回调由 `latestApprovalDecision` 和 `resumeIdempotencyKey` 识别，不再次调用下游 Agent；同一 JVM 内还按 taskId 串行化恢复操作。

## 8. 第一批改造范围

本轮先做：

1. 引入官方 Graph 依赖
2. 新增官方 Graph 状态骨架
3. 新增官方 Graph 线程/配置骨架
4. 为后续编译入口准备统一门面

本轮不做：

- 一次性把所有流程切到官方 Graph
- 一次性替换审批、并行、handoff 全链路

## 9. 验收标准

当前已验证：

- `agent-service` 已引入官方 Graph 依赖并完成 Spring AI 依赖树检查
- 官方 Graph 状态模型、顶层 Graph 门面和数据库 checkpoint 适配器已入库
- 审批暂停/恢复最小 Graph 集成测试通过
- `mvn -pl agent-service test` 通过（23 项）
- `mvn -pl agent-service -am -DskipTests compile` 通过
- `openspec validate migrate-supervisor-graph-approval-routing --strict` 通过

后续验收：

- 原生并行 fan-out/fan-in 与 all-of/any-of 策略
- 旧 checkpoint envelope 自动迁移、服务重启恢复和多实例数据库幂等
- SkillMatch/WorkflowPlan 非法输入的失败分支和端到端测试
- MCP/A2A smoke test 与带 Nacos/数据库的最小微服务启动

