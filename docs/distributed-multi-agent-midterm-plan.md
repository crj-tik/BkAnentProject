# 分布式多 Agent 中期改造方案

## 1. 目标

基于当前已经完成的分布式多 Agent、Spring AI Alibaba Graph、A2A、Memory Service、RocketMQ 事件流和审批子图骨架，本阶段目标不再是继续快速扩能力，而是把现有实现从“能跑”提升到“可长期维护、可治理、可扩展”。

本阶段重点解决两类问题：

- 文档中“部分实现但未达到目标”的能力
- 文档中“尚未完成”的治理、收口和异步闭环能力

本阶段不建议优先继续新增大量业务 Agent 或复杂业务链，而应先补齐协议、artifact、权限、事件驱动、workflow async 和可观测性。

---

## 2. 当前实现基线

当前项目已经具备以下基础：

- `supervisor-agent-service` 主控编排已迁入官方 Spring AI Alibaba Graph 主链
- `listing / marketing / media / trade / contract / settlement / notification` 七类子 Agent 已具备最小 A2A 能力
- `memory-service` 已支持 `session shared memory`、`task artifact memory`、`handoff relation`
- `ApprovalSubgraph` 已统一收口在主 Agent
- `parallel / handoff / resume / route` 主链已打通
- `RocketMQ` 已接入 session event bus
- `A2A async task/status/stream` 已有最小骨架
- `marketing -> media -> publish_prepare -> publish -> notification` 已有最小闭环

当前最明显的缺口主要是：

- 大对象还没有严格改走 artifact
- `notification-service` 还不是事件驱动消费者
- 权限、限流、灰度、压测、观测基本未系统落地
- workflow 级 async 还没有完整管理面
- A2A streaming 还是最小 SSE/轮询桥接，不是完整事件驱动闭环
- `memory-service` 还没有 task 级 artifact 权限控制

---

## 3. 中期改造原则

### 3.1 先收口，再扩展

先冻结协议、统一对象边界、补权限和事件驱动，再继续扩更多 Agent 或复杂业务链。

### 3.2 优先做“公共底座”

优先改：

- `common`
- `agent-service`
- `memory-service`

后改：

- `marketing-content-service`
- `media-worker-service`
- `contract-service`
- `settlement-service`
- `notification-service`

### 3.3 不重复造第二套模型

已有这些对象继续沿用：

- `AgentCard`
- `AgentTaskInvokeRequest`
- `AgentTaskInvokeResponse`
- `AgentHandoffPacket`
- `ApprovalRequest`
- `ApprovalDecision`
- `ArtifactMeta`
- `ArtifactCreateRequest`
- `ArtifactQueryResponse`

中期改造只做收紧和补充，不再并行创建新版本 DTO。

---

## 4. 阶段划分

建议按 6 个阶段推进：

1. 协议收口
2. Artifact 化改造
3. 权限与边界
4. 事件驱动升级
5. Workflow Async 正式化
6. 观测与治理

---

## 5. 阶段一：协议收口

### 5.1 目标

冻结跨服务协议，停止 DTO 和字段持续漂移。

### 5.2 要做的事情

- 冻结 `A2A sync / async / stream` 三套接口
- 冻结 `AgentCard` 字段含义
- 明确 `supportsStreaming` 和 `supportsAsyncTask` 的真实声明标准
- 冻结 `Approval*`、`Artifact*`、`AgentHandoffPacket` 字段约束
- 定义 `structuredOutput` 可直接承载的字段上限
- 定义哪些内容必须改走 `artifactId`

### 5.3 涉及模块

- `common`
- `agent-service`
- 所有子 Agent 服务

### 5.4 重点类

- [AgentCard.java](/D:/project/BkAnentProject/BkAnentProject/common/src/main/java/com/bkanent/common/agent/AgentCard.java:1)
- [AgentTaskInvokeRequest.java](/D:/project/BkAnentProject/BkAnentProject/common/src/main/java/com/bkanent/common/agent/AgentTaskInvokeRequest.java:1)
- [AgentTaskInvokeResponse.java](/D:/project/BkAnentProject/BkAnentProject/common/src/main/java/com/bkanent/common/agent/AgentTaskInvokeResponse.java:1)
- [AgentHandoffPacket.java](/D:/project/BkAnentProject/BkAnentProject/common/src/main/java/com/bkanent/common/agent/AgentHandoffPacket.java:1)
- [ApprovalRequest.java](/D:/project/BkAnentProject/BkAnentProject/common/src/main/java/com/bkanent/common/agent/ApprovalRequest.java:1)
- [ArtifactMeta.java](/D:/project/BkAnentProject/BkAnentProject/common/src/main/java/com/bkanent/common/agent/ArtifactMeta.java:1)

### 5.5 验收标准

- 所有子 Agent `AgentCard` 的 `supportsStreaming / supportsAsyncTask` 与真实能力一致
- `A2A` 同步、异步、stream 三套接口固定
- 形成一份字段白名单与字段边界约束

---

## 6. 阶段二：Artifact 化改造

### 6.1 目标

把当前仍然直接塞在 `structuredOutput` 里的大对象逐步改成 artifact 引用。

### 6.2 要做的事情

- 把这些内容优先改成 artifact：
  - `publishPayload`
  - 营销长文案正文
  - 合同详细分析结果
  - 结算汇总明细
  - 媒体任务明细和媒体结果
- 统一 `artifactType`
- `memory-service` 增加 artifact version 和 meta 约束
- supervisor 恢复和 handoff 时优先走 artifact 引用

### 6.3 推荐顺序

1. `marketing-content-service`
2. `media-worker-service`
3. `contract-service`
4. `settlement-service`

### 6.4 涉及模块

- `memory-service`
- `agent-service`
- `marketing-content-service`
- `media-worker-service`
- `contract-service`
- `settlement-service`

### 6.5 重点类

- [TaskArtifactMemoryService.java](/D:/project/BkAnentProject/BkAnentProject/memory-service/src/main/java/com/bkanent/memory/service/TaskArtifactMemoryService.java:1)
- [RemoteTaskArtifactStore.java](/D:/project/BkAnentProject/BkAnentProject/agent-service/src/main/java/com/bkanent/agent/workflow/RemoteTaskArtifactStore.java:1)
- [PersistArtifactsNode.java](/D:/project/BkAnentProject/BkAnentProject/agent-service/src/main/java/com/bkanent/agent/graph/node/PersistArtifactsNode.java:1)
- [MarketingAgentServiceImpl.java](/D:/project/BkAnentProject/BkAnentProject/marketing-content-service/src/main/java/com/bkanent/marketing/service/impl/MarketingAgentServiceImpl.java:1)
- [MediaAgentServiceImpl.java](/D:/project/BkAnentProject/BkAnentProject/media-worker-service/src/main/java/com/bkanent/media/service/impl/MediaAgentServiceImpl.java:1)

### 6.6 验收标准

- 大结果不再直接完整塞进 `structuredOutput`
- `artifactId` 成为发布、媒体、合同、结算四条链路的主引用
- `memory-service` 可按 task、artifactId、type 稳定查询

---

## 7. 阶段三：权限与边界

### 7.1 目标

把文档里要求的权限边界真正落地。

### 7.2 要做的事情

- `memory-service` 增加 task 级 artifact 访问控制
- handoff packet 做最小必要字段过滤
- supervisor 调用子 Agent 前接入权限校验
- skill / tool / RAG namespace 做最小权限收敛
- 区分：
  - supervisor 可见信息
  - 当前 agent 可见信息
  - 下游 handoff agent 可见信息

### 7.3 涉及模块

- `agent-service`
- `memory-service`
- `auth-service`
- `common`

### 7.4 重点类

- [AuthPermissionRpcService.java](/D:/project/BkAnentProject/BkAnentProject/common/src/main/java/com/bkanent/common/rpc/AuthPermissionRpcService.java:1)
- [MemoryStoreClient.java](/D:/project/BkAnentProject/BkAnentProject/agent-service/src/main/java/com/bkanent/agent/memory/MemoryStoreClient.java:1)
- [HandoffNode.java](/D:/project/BkAnentProject/BkAnentProject/agent-service/src/main/java/com/bkanent/agent/graph/node/HandoffNode.java:1)
- [AgentHandoffPacket.java](/D:/project/BkAnentProject/BkAnentProject/common/src/main/java/com/bkanent/common/agent/AgentHandoffPacket.java:1)

### 7.5 验收标准

- artifact 查询具备 task 级授权校验
- handoff packet 不再透传过多上下文
- supervisor 调用高敏感子 Agent 前具备权限判定

---

## 8. 阶段四：事件驱动升级

### 8.1 目标

把当前“事件总线基础已接入，但业务上仍偏同步调用”的状态升级成真正事件驱动协作。

### 8.2 要做的事情

- 规范 RocketMQ topic/tag：
  - `task_status`
  - `approval`
  - `handoff`
  - `artifact`
  - `publish`
- `notification-service` 从被 handoff 调用，升级为优先消费事件
- 把 `publish / approval / handoff / artifact.created` 事件标准化
- 消费端补幂等和重复消费保护
- 将 async stream 尽量从 controller 轮询桥接收口到事件驱动桥接

### 8.3 涉及模块

- `agent-service`
- `notification-service`
- `marketing-content-service`
- `settlement-service`
- `common`

### 8.4 重点类

- [RocketMqSessionEventBus.java](/D:/project/BkAnentProject/BkAnentProject/agent-service/src/main/java/com/bkanent/agent/stream/RocketMqSessionEventBus.java:1)
- [SessionStreamEvent.java](/D:/project/BkAnentProject/BkAnentProject/common/src/main/java/com/bkanent/common/agent/SessionStreamEvent.java:1)
- [NotificationAgentServiceImpl.java](/D:/project/BkAnentProject/BkAnentProject/notification-service/src/main/java/com/bkanent/notification/service/impl/NotificationAgentServiceImpl.java:1)

### 8.5 验收标准

- `notification-service` 可以消费关键业务事件
- 通知链不再只能靠 supervisor 显式 handoff
- async 状态事件有统一 topic/tag 规则

---

## 9. 阶段五：Workflow Async 正式化

### 9.1 目标

把当前只完成到 `tasks/async` 的状态升级成完整 workflow 异步管理面。

### 9.2 要做的事情

- 给 `supervisor/workflows` 增加 async create/status/stream/cancel
- 区分：
  - child agent async task
  - supervisor workflow async task
- 审批恢复链纳入 workflow async 生命周期
- 根据需要增加 workflow async 持久化表
- 支持重试、取消、失败恢复

### 9.3 涉及模块

- `agent-service`
- `memory-service`
- `common`

### 9.4 重点类

- [SupervisorAsyncTaskService.java](/D:/project/BkAnentProject/BkAnentProject/agent-service/src/main/java/com/bkanent/agent/service/SupervisorAsyncTaskService.java:1)
- [SupervisorWorkflowService.java](/D:/project/BkAnentProject/BkAnentProject/agent-service/src/main/java/com/bkanent/agent/service/SupervisorWorkflowService.java:1)
- [GraphCheckpointStore.java](/D:/project/BkAnentProject/BkAnentProject/agent-service/src/main/java/com/bkanent/agent/workflow/GraphCheckpointStore.java:1)

### 9.5 验收标准

- `workflow async` 有独立 create/status/stream/cancel
- 审批中的 workflow 可以作为异步任务长期存在
- 复杂链路不再只能靠同步请求等待结果

---

## 10. 阶段六：观测与治理

### 10.1 目标

把当前 `traceId + 事件打印` 提升成可审计、可监控、可压测的体系。

### 10.2 要做的事情

- 统一查询维度：
  - `traceId`
  - `taskId`
  - `approvalId`
  - `artifactId`
  - `asyncTaskId`
- 增加 graph 节点级埋点
- 增加 handoff 审计
- 增加 async task 指标
- 增加审批超时、子 Agent 超时、MQ 积压监控
- 补限流、灰度、压测

### 10.3 涉及模块

- `agent-service`
- `memory-service`
- `gateway`
- 所有子 Agent 服务

### 10.4 推荐落地顺序

1. 统一日志与查询维度
2. 指标埋点
3. 审计与告警
4. 限流与灰度
5. 压测

### 10.5 验收标准

- 可以按 `traceId/taskId/approvalId/artifactId/asyncTaskId` 查链路
- 可以看见 graph 节点、审批、handoff、async 的关键指标
- 具备上线前压测和灰度能力

---

## 11. 推荐执行顺序

建议严格按下面顺序推进：

1. 协议收口
2. Artifact 化改造
3. 权限与边界
4. 事件驱动升级
5. Workflow Async 正式化
6. 观测与治理

不建议的顺序：

- 先继续加更多业务 Agent
- 先继续扩复杂业务链
- 最后再回头补 artifact、权限、事件驱动、async 和治理

---

## 12. 每阶段验收门槛

### 阶段 1 通过门槛

- DTO 不再频繁变动
- `AgentCard` 能力声明真实有效
- A2A 三套接口固定

### 阶段 2 通过门槛

- 大结果统一经 artifact 出口
- 发布链、媒体链、合同链、结算链都有 artifact 引用

### 阶段 3 通过门槛

- artifact 有 task 级鉴权
- handoff 包只带最小必要信息

### 阶段 4 通过门槛

- notification 可消费业务事件
- 事件 topic/tag 规范稳定

### 阶段 5 通过门槛

- workflow async 可查询、可持续、可恢复

### 阶段 6 通过门槛

- 可按关键 ID 查整条链
- 具备告警、限流、压测和灰度基础

---

## 13. 结论

当前项目已经完成了“分布式多 Agent 主骨架”和“主 Agent Graph 化”的第一轮建设。中期阶段的重点，不是继续无节制扩业务链，而是把现有实现收口成：

- 协议稳定
- 产物归档清晰
- 权限边界明确
- 事件驱动协作
- workflow async 完整
- 可观测可治理

只有完成这 6 个阶段，后续再继续扩更多 Agent、更多业务链路，返工成本才会明显下降。
---

## 14. 当前进度补充

- 阶段 1 已完成：A2A 协议与 AgentCard 能力声明已收口
- 阶段 2 已完成主链：marketing/media/contract/settlement 已补 artifact 引用
- 阶段 3 已基本完成：workflow/artifact 查询、child invoke、MCP/RAG/chat 权限已接入
- 阶段 4 已完成：notification-service 已转事件优先，并具备消费去重与失败查询
- 阶段 5 已完成主链：workflow async 已支持 create/status/stream/cancel/retry
- 阶段 6 进行中：
  - 已新增 `/agent/supervisor/diagnostics`
  - 已新增 `/agent/supervisor/event-audit`
  - 已补关键 metadata：`approvalId / artifactId / asyncTaskId / asyncWorkflowId`
  - 已开始补 `stage / durationMs` 观测字段

阶段 6 剩余重点：

- 继续补 graph 节点级事件语义
- 继续沉淀 metrics/告警字段
- 视需要补压测、限流、灰度落地清单

### 14.1 第 6 阶段已补充的观测字段

- `stage`
  - `approval`
  - `workflow`
  - `security`
  - `async_task`
  - `async_workflow`
  - `graph`
- `durationMs`
  - 已接入 `supervisor.async.*`
  - 已接入 `supervisor.workflow_async.*`
- 关键检索 ID
  - `approvalId`
  - `artifactId`
  - `asyncTaskId`
  - `asyncWorkflowId`

### 14.2 下一步 metrics / 告警建议

- graph 子图维度
  - `graph.subgraph.started/completed/failed`
  - 关键子图耗时分位值
- async 维度
  - `supervisor.async.failed` 次数
  - `supervisor.workflow_async.failed` 次数
  - `durationMs` P95 / P99
- approval 维度
  - `task.waiting_approval` 堆积数
  - `task.approval_rejected` 比例
- MQ / 通知维度
  - notification 消费失败数
  - 重复事件跳过数

### 14.3 已落地的 metrics 命名

- `bk.agent.graph.subgraph.duration`
  - tags: `subgraph`, `status`
- `bk.agent.graph.subgraph.count`
  - tags: `subgraph`, `status`
- `bk.agent.async.task.duration`
  - tags: `mode`, `status`
- `bk.agent.async.task.count`
  - tags: `mode`, `status`
- `bk.agent.async.workflow.duration`
  - tags: `status`
- `bk.agent.async.workflow.count`
  - tags: `status`
- `bk.agent.security.permission.denied`
  - tags: `action`
- `bk.notification.workflow.event.duration`
  - tags: `eventType`, `status`
- `bk.notification.workflow.event.count`
  - tags: `eventType`, `status`
- `bk.notification.workflow.consume.count`
  - tags: `status`

### 14.4 建议告警阈值

- `bk.agent.graph.subgraph.duration`
  - `parallel_agent` P95 > 5000ms 告警
  - `handoff` P95 > 3000ms 告警
  - `resume` / `completion` P95 > 1500ms 告警
- `bk.agent.async.workflow.count{status="FAILED"}`
  - 5 分钟内连续 > 5 告警
- `bk.agent.async.task.count{status="FAILED"}`
  - 5 分钟内连续 > 10 告警
- `bk.agent.security.permission.denied`
  - 单用户 10 分钟内 > 20 次告警
- `bk.notification.workflow.consume.count{status="FAILED"}`
  - 5 分钟内 > 3 告警
- `bk.notification.workflow.consume.count{status="DUPLICATE_SKIPPED"}`
  - 15 分钟内异常突增时告警，用于排查重复投递

### 14.5 第 6 阶段收尾建议

- 接 Prometheus 抓取 actuator metrics
- 给高频子图建立 Grafana 面板
- 基于 `event-audit` 与 `diagnostics` 做运维排障 SOP
- 补第 6 阶段剩余的压测、限流、灰度落地文档

### 14.6 第 6 阶段已落地的入口治理

- `agent-service` 已增加 `SupervisorGovernanceService`
- supervisor 4 个入口已接入口级限流
  - `/agent/supervisor/tasks`
  - `/agent/supervisor/tasks/async`
  - `/agent/supervisor/workflows`
  - `/agent/supervisor/workflows/async`
- 当前限流策略为固定窗口计数
  - 优先按 `userId`
  - 其次按 `sessionId`
  - 否则落到 `anonymous`
- 当前配置项：
  - `agent.distributed.rate-limit.enabled`
  - `agent.distributed.rate-limit.window-seconds`
  - `agent.distributed.rate-limit.default-per-window`
  - `agent.distributed.rate-limit.supervisor-tasks-per-window`
  - `agent.distributed.rate-limit.supervisor-async-tasks-per-window`
  - `agent.distributed.rate-limit.supervisor-workflows-per-window`
  - `agent.distributed.rate-limit.supervisor-async-workflows-per-window`
- 限流失败统一返回：
  - `RATE_LIMITED`

灰度方面：

- `agent-service` 已增加灰度上下文注入
- 当前支持的灰度命中维度：
  - `userId`
  - `sessionId`
  - `domain`
- 当前配置项：
  - `agent.distributed.gray-release.enabled`
  - `agent.distributed.gray-release.strategy-version`
  - `agent.distributed.gray-release.prefer-async-a2a`
  - `agent.distributed.gray-release.user-ids`
  - `agent.distributed.gray-release.session-ids`
  - `agent.distributed.gray-release.domains`
- 命中灰度后会在 workflow/task context 注入：
  - `grayRelease=true`
  - `grayStrategyVersion`
  - `forceAsyncA2a=true`（当 `preferAsyncA2a=true`）

后续建议：

- 将固定窗口限流升级为可替换的 Redis/网关级限流实现
- 把灰度命中结果补入审计事件 metadata
- 将 `strategyVersion` 进一步下沉到 Graph route 和 child-agent 路由选择

当前状态补充：

- 限流已抽象为 `SupervisorRateLimiter`
- 当前默认实现为内存版 `InMemorySupervisorRateLimiter`
- 后续替换 Redis/网关限流时不需要再改 controller 与 governance 主逻辑
- 灰度命中结果已开始进入关键审计事件 metadata
  - `supervisor.async.accepted`
  - `supervisor.workflow_async.accepted`
  - `task.started`
- `SupervisorTaskResponse` 也已附带 `governanceMetadata`

### 14.7 灰度策略下沉与压测清单

灰度策略下沉现状：

- `grayStrategyVersion` 已进入：
  - `SelectAgentNode`
  - `ParallelInvokeNode`
  - `HandoffNode`
  - `RouteDecisionNode`
- 当前支持两类灰度钩子：
  - `preferredAgentIds`
    - 按 `domain -> agentId` 覆盖 child-agent 选择
  - `routeOverrideDomains`
    - 当前支持 `parallel -> nextDomain` 覆盖并行后路由
- 这些配置会随灰度上下文进入：
  - handoff 下游上下文
  - async accepted 审计事件
  - `task.started` 审计事件

建议的压测执行顺序：

1. 单 Agent 主链压测
   - `listing.search`
   - `marketing.generate_copy`
2. 并行链压测
   - `listing + trade`
   - 验证 `parallel_agent` P95/P99
3. 审批恢复链压测
   - `waiting_approval -> approved`
   - `waiting_approval -> rejected -> regenerate`
4. 发布闭环压测
   - `marketing -> media -> publish_prepare -> publish -> notification`
5. async workflow 压测
   - `create/status/stream/cancel/retry`
6. 灰度与限流联合压测
   - 灰度 session 命中
   - 灰度 route override
   - `RATE_LIMITED` 返回与审计事件

建议的压测验收：

- 单 Agent 主链成功率不低于 `99%`
- 并行链无重复 handoff、无重复 artifact 持久化
- 审批恢复链无重复 resume、无重复通知
- async workflow 在取消和重试场景下状态一致
- 灰度命中时 diagnostics/event-audit 可见 `grayStrategyVersion`
- 限流命中时返回 `RATE_LIMITED`，且无服务级异常堆积
