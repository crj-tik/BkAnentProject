# 官方 Graph 改造清单

## 1. 结论

当前项目已经完成了分布式多 Agent 的第一阶段主干：

- `A2A + AgentCard + AgentRegistry` 已落地
- `listing / marketing / media / trade / contract` 五个子 Agent 已接入
- `memory-service`、`artifact`、`handoff relation` 已具备最小闭环
- `RocketMQ` 事件总线已接入
- 审批、并行、handoff、checkpoint 已跑通

因此当前优先级应切换为：

1. 主 Agent 改造为官方 Spring AI Alibaba Graph
2. 再继续补 `distributed-multi-agent-*.md` 中剩余业务功能

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

- 自定义 `SupervisorGraphPlanner`
- 自定义 `SingleAgentSubgraph`
- 自定义 `ParallelAgentSubgraph`
- 自定义 `ApprovalSubgraphService`
- 自定义 `GraphCheckpointStore`
- `SupervisorWorkflowService` 统一控制审批、并行、handoff

### 与官方 Graph 的核心差异

- 当前不是官方 `StateGraph`
- 当前审批不是官方可恢复 `Subgraph`
- 当前 checkpoint 不是官方 Graph runtime 的 `Checkpointer`
- 当前节点虽然已经拆分，但仍大量依赖 service 手工串接

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

状态：进行中

### P0-2 建立官方 Graph 状态模型

目标：

- 用官方 `OverAllState` 承载 supervisor 图状态
- 明确 Graph state keys 与策略

改造项：

- 新增 `OfficialSupervisorGraphState`
- 新增 `OfficialSupervisorGraphKeys`
- 新增 `OfficialSupervisorGraphSchema`

状态：待完成

### P0-3 建立官方 Graph 编译入口

目标：

- 新增官方 `StateGraph` 编译入口
- 先跑通单链路：`LoadSession -> ParseIntent -> PlanTask -> SelectAgent -> Invoke -> Persist -> Finish`

改造项：

- 新增 `OfficialSupervisorGraphFactory`
- 新增 `OfficialCompiledSupervisorGraphHolder`

状态：待完成

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

状态：待完成

### P0-5 单 Agent 主链切换

目标：

- `SupervisorWorkflowService.startWorkflow` 单链路改走官方 Graph
- `SupervisorTaskService.submitTask` 单链路改走同一个官方 Graph 门面

状态：待完成

### P0-6 审批子图切换

目标：

- `ApprovalSubgraphService` 迁移为官方可恢复子图
- 用 `threadId + checkpointer` 驱动暂停与恢复

状态：待完成

### P0-7 并行子图切换

目标：

- 并行调用迁移到官方 Graph 并行分支
- 汇聚后继续走 route decision

状态：待完成

### P0-8 Handoff 子图切换

目标：

- handoff 迁移为显式图节点流转
- 保留 `memory-service` handoff relation 落点

状态：待完成

### P0-9 Checkpointer 适配

目标：

- 以官方 Graph `Checkpointer` 接管当前 checkpoint 逻辑
- 当前自定义 `GraphCheckpointStore` 逐步退为兼容层或查询层

状态：待完成

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

## 7. 第一批改造范围

本轮先做：

1. 引入官方 Graph 依赖
2. 新增官方 Graph 状态骨架
3. 新增官方 Graph 线程/配置骨架
4. 为后续编译入口准备统一门面

本轮不做：

- 一次性把所有流程切到官方 Graph
- 一次性替换审批、并行、handoff 全链路

## 8. 验收标准

第一批验收只看：

- `agent-service` 已引入官方 Graph 依赖
- 官方 Graph 状态模型已入库代码结构
- 单链路改造入口已经具备可继续迁移的代码骨架
- 全项目编译通过

第二批验收再看：

- 单 Agent 主链正式切换到官方 Graph
- 审批恢复正式切到官方 Graph Checkpointer

