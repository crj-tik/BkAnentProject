## Context

See `proposal.md` for the motivation and `specs/supervisor-streaming-orchestration/spec.md` for the externally visible contract. 当前 Supervisor 已经有基于 `sessionId` 的 SSE、Session EventBus、本地/ RocketMQ 事件分发、Graph checkpoint 和 A2A 子 Agent 调用路径，但 A2A 客户端只有阻塞调用与异步状态轮询，子 Graph 也以最终状态调用为主。现有事件审计可以保存事件，但没有与 SSE 订阅原子衔接的事件序号和回放游标。

本设计假设 Supervisor 是客户端唯一的流式出口。子 Agent 的传输方式可以不同，Supervisor 将远程 A2A 流、本地子图流、异步任务状态和最终响应归一化为同一类会话事件。Graph checkpoint 仍然保存流程状态，实时 token 不写入 checkpoint。

## Goals / Non-Goals

**Goals:**

- 让一个 `sessionId` 对应一条可持续的 Supervisor 逻辑流，覆盖顺序 handoff、并行分支、审批等待、恢复、完成和失败。
- 在 Supervisor 内部提供子 Agent 流事件适配器，将安全的 delta、工具进度、生命周期和最终结果转发到统一事件出口。
- 为事件提供稳定 ID、会话内序号、子运行标识和回放能力，支持创建任务与订阅之间的竞态以及断线重连。
- 维持现有同步/异步请求、A2A 最终结果、审批回调和 Graph 路由兼容，并允许按 Agent 能力逐步启用流式桥接。
- 使结构化结果、artifact 引用和路由信息成为 Agent 之间的正式传递内容，而不是依赖 token 拼接。

**Non-Goals:**

- 本变更不改各个 subagent 的业务逻辑、模型调用或 A2A server 实现；它们继续作为同步、异步或未来流式事件的来源。
- 不把 Supervisor 改造成客户端直连多个子 Agent 的网关，也不让客户端感知 A2A 地址或 Agent 连接切换。
- 不把 token 增量作为 Graph 状态、路由条件、审批条件或恢复依据。
- 不转发私有思考过程；只允许明确标记为用户可见的输出和进度事件进入外部流。

## Decisions

### 1. Supervisor 是唯一外部流出口

保留现有按 `sessionId` 订阅的 Supervisor SSE 作为客户端入口。Supervisor 内部无论调用远程 A2A、同进程子图还是异步任务，都向同一个事件发布接口写入事件。客户端不需要也不允许在 Agent 切换时重新连接子 Agent。

这样可以把“传输连续性”和“Graph 流程连续性”分开：SSE 连接只负责实时交付，Graph thread/checkpoint 负责恢复和路由。审批等待或子 Agent 长任务期间，客户端可以保持同一逻辑流，也可以断开后通过游标恢复。

### 2. 采用统一的可演进事件 envelope

在现有会话事件基础上增加可选的 `eventId`、`sequence`、`childRunId`、`parentTaskId`、`branchId`、`phase`、`terminal` 和 `visibility` 信息，同时保留现有 `sessionId`、`taskId`、`agentId`、`eventType`、`content`、`metadata`、`traceId`、`timestamp` 字段。DTO 扩展采用可选字段，并保留旧构造方式或兼容映射，使当前生命周期事件消费者无需一次性升级。

事件类型分为四组：

- 工作流控制：`workflow.started`、`workflow.waiting_approval`、`workflow.completed`、`workflow.failed`。
- Agent 生命周期：`agent.started`、`agent.completed`、`agent.failed`、`handoff.started`、`handoff.completed`。
- 执行过程：`agent.delta`、`agent.tool.started`、`agent.tool.completed`、`agent.progress`。
- 兼容状态：现有 `a2a.async.*`、`supervisor.workflow_async.*` 等事件继续保留，并补充相同的 envelope 元数据。

`visibility` 至少区分 `user`、`progress` 和 `internal`。只有 `user` 与允许的 `progress` 事件进入外部 SSE；`internal` 事件可以留在审计或内部诊断通道。

### 3. 使用持久化事件审计作为回放源

分布式模式下，每个事件先由 Supervisor 事件发布层分配稳定 ID 和会话内序号，再写入事件审计，最后投递到 RocketMQ 和本地订阅者。事件审计记录作为回放源，RocketMQ 负责跨实例实时分发，SSE subscriber 负责当前连接交付。事件写入和投递需要以 event ID 做幂等，重复消息不得导致客户端看到无法识别的重复事件。

本地模式可以使用受限内存环形缓存，但仍使用同样的事件 envelope；如果配置了数据库审计，则优先从数据库回放。事件回放必须按 `(sessionId, taskId, sequence)` 查询，并支持 `afterEventId` 或 `afterSequence`。SSE 响应使用标准 SSE `id` 字段，使浏览器的 `Last-Event-ID` 能映射到回放游标。

事件序号不使用客户端时间戳推导。分布式模式使用持久化分配结果作为排序依据，并在同一 session/task 内保证单调递增；时间戳只用于展示和过期清理。

### 4. 为子 Agent 调用增加 Supervisor 侧 stream bridge

在现有 A2A client/execution 抽象上增加流式调用能力，但不删除阻塞 `invoke`、异步 `submitAsync/queryAsyncStatus`。Supervisor 调用时按照 Agent card 能力和请求偏好选择路径：

```text
stream requested + child streaming supported
    -> consume child event stream
    -> normalize and publish session events
    -> accumulate final structured response

otherwise
    -> existing blocking invoke, or async task polling
    -> publish lifecycle/progress events
    -> return final structured response
```

流式调用必须创建一个 Supervisor 侧 `childRunId`，复用根工作流的 `sessionId`、`taskId` 和 `traceId`，并记录 `parentTaskId`、目标 Agent、领域、意图和分支信息。子 Agent 的原始事件不能直接穿透到客户端，必须经过安全过滤、字段归一化、序号分配和事件发布。

流桥接器维护一个最终结果累加器。中间 delta 只发布事件，不更新 Graph；收到子 Agent 的终端结构化结果后，累加器返回现有 `AgentTaskInvokeResponse`，Graph 才继续执行 `route-after-execution`、artifact 持久化或下一次 handoff。

### 5. 本地子图与远程 A2A 使用同一适配接口

本地子 Graph 使用其 stream/事件迭代能力产生子图节点、模型消息或自定义进度事件；远程子 Agent 使用 A2A streaming 能力产生事件。两者都转换成内部 `ChildAgentEvent`，再由同一个 `SupervisorStreamBridge` 处理。这样不会把 LangGraph/Spring AI 的具体事件格式暴露到公共 API，也便于后续改造不同类型的 subagent。

子图的 checkpoint/thread 标识应使用带 `childRunId` 的命名空间，避免子图状态和顶层 Supervisor 状态发生覆盖；顶层 Graph 仍使用原有的 `taskId` 线程标识。子图完成后只把最终响应、artifact 和路由所需字段合并回顶层状态。

### 6. handoff 以结构化上下文为边界

Supervisor 在 handoff 前从顶层状态组装下游请求，传递：

- 根会话与工作流关联信息、当前 `parentTaskId`、`childRunId` 和 `traceId`；
- 下游 Agent 的 domain、intent、instruction 和约束；
- 上游结构化结果的白名单字段；
- artifact ID、artifact 摘要和可引用的持久化结果；
- `requestStream`、能力降级和治理上下文。

下游 Agent 不依赖上游 token 流重建上下文。`nextHints`、结构化结果和 artifact 引用只在终端结果到达后参与路由。无效的目标、缺少关联信息或无法验证的上下文在调用下游前进入可诊断失败分支。

### 7. 订阅、回放和实时交付必须原子衔接

流订阅流程先确定 session/task 权限和回放游标，读取游标之后的持久化事件，再注册实时 subscriber，并使用序列去重。实现上可以使用短暂的订阅锁、事件版本检查或等价机制，避免“回放结束到注册实时订阅”之间丢事件。

异步任务接口继续先返回任务标识；客户端可以随后订阅。同步流式接口可以采用单个 SSE 请求包住任务启动，也可以要求调用方先提供 `sessionId`，但两种方式都必须经过相同的持久化回放路径。

### 8. 取消和断开只影响交付，不隐式终止工作流

客户端 SSE 断开不能直接视为用户取消，Graph 和子 Agent 继续执行并保存状态。显式取消请求才触发 Supervisor 的取消状态和下游取消传播；如果下游协议暂不支持取消，Supervisor 至少停止继续路由并在最终事件中报告取消与下游实际状态的差异。

## Risks / Trade-offs

- [事件量膨胀] token delta 会显著增加 RocketMQ、数据库审计和 SSE 压力 → 对 delta 设置大小/频率合并策略，区分实时外发与审计保留策略，并保留生命周期事件的完整性。
- [分布式排序成本] 多实例同时发布同一 session 的事件需要集中分配序号 → 使用持久化序列/事件写入结果作为顺序源，接受少量数据库写入成本，不用本地时间戳排序。
- [子 Agent 能力不一致] 当前 subagent 可能只支持最终响应或异步任务 → 保留 blocking/async fallback，并让能力标识控制桥接路径，不能伪造 token 流。
- [模型输出泄露] 原始子 Agent 事件可能包含内部思考或敏感工具参数 → 在 bridge 层执行 visibility 白名单和内容过滤，内部事件只进入受控审计。
- [回放与实时重复] RocketMQ 重投、SSE 重连和回放可能产生重复事件 → 使用稳定 event ID、客户端可识别的 SSE id 和服务端幂等记录。
- [Graph 与旁路事件不一致] 进程崩溃可能留下已发布但未完成的 delta → delta 只作为观察信息，最终状态以 checkpoint 和终端事件为准；恢复时重新发布必要的生命周期状态而不是伪造缺失 token。

## Migration Plan

1. 先以兼容字段和事件发布层改造 Supervisor，保留现有事件名称、阻塞调用和异步轮询；验证非流式调用行为不变。
2. 增加事件 ID/序号、审计持久化和 SSE replay，再将现有生命周期事件接入回放路径，先解决订阅竞态和断线恢复。
3. 在 Supervisor A2A client 增加 stream bridge，并以配置或 Agent capability 逐个启用；未启用的 Agent 自动走现有 fallback。
4. 接入本地子 Graph 的事件桥接，验证顺序 handoff、并行分支、审批暂停/恢复和最终结果合并。
5. 增加指标、限流、delta 合并和取消传播；确认稳定运行后，再在独立变更中逐个改造 subagent 的流式实现。

回滚时关闭 Supervisor 流式桥接开关，继续使用现有 blocking/async fallback 和生命周期 SSE。新增 DTO 字段、审计字段和事件类型均采用向后兼容方式，回滚不需要删除历史事件；如果新序号字段不可用，则禁止 replay 增量并退回状态查询，避免错误地宣称可恢复。

## Open Questions

无。A2A SDK 的具体 streaming API 选择和 delta 合并窗口属于实现层选择，不改变本变更定义的公共行为或任务边界。
