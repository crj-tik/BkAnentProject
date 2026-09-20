## Context

当前官方 A2A 客户端已经能够接收 Message、Task、Task 状态更新和 Artifact 更新，并在流结束后生成 `AgentTaskInvokeResponse`。但现有转换只把输出文本放在 `structuredOutput.output` 中，A2A JSON/Data part 和子 Agent 约定的业务 JSON 没有被展开；流式执行层虽然暂存了终态响应，但发布到会话流时只发送文本和 metadata。

Supervisor 的本地 `taskId` 用于 LangGraph checkpoint、权限校验和工作流恢复，不能被远端 A2A Task ID 替换。现有 `SessionStreamEvent` 的 metadata 会进入审计存储并参与 SSE 重放，因此可以在不破坏已有事件构造器和数据库表结构的前提下承载终态响应。

## Goals / Non-Goals

**Goals:**

- 在 Supervisor 侧建立统一的 A2A Message、Task、Artifact 和流式事件响应归一化规则。
- 让结构化 JSON/Data 输出直接成为 `AgentTaskInvokeResponse.structuredOutput` 的业务字段，同时保留原始文本。
- 在内部响应和会话终态事件中同时保留本地 taskId、远端 A2A taskId、Artifact IDs、状态和错误。
- 让同步调用、实时流式调用和异步状态查询复用同一套归一化逻辑。
- 让终态结构化响应能通过现有 SSE 审计和重放链路到达客户端。
- 用测试覆盖解析、关联、流式终态、重放和 Supervisor 路由。

**Non-Goals:**

- 本变更不修改各 SubAgent 的 ReactAgent、工具或 A2A 服务端实现。
- 本变更不更换官方 A2A SDK、SSE 传输方式或 LangGraph checkpoint 机制。
- 本变更不重新设计所有领域 JSON 字段；对现有领域字段采用兼容性解析，SubAgent 输出契约统一放在后续变更处理。
- 本变更不把中间增量事件强行转换为完整业务对象；只有终态事件需要携带完整结构化结果。

## Decisions

### 1. 在 Supervisor 侧集中实现响应归一化

增加一个仅负责 A2A 结果映射的组件，接收 Text、JSON/Data part、Task、Artifact 和状态信息，输出现有 `AgentTaskInvokeResponse`。同步 `invoke()`、流式终态处理和异步 `queryAsyncStatus()` 都调用该组件。

选择集中归一化而不是分别修改三条调用路径，是为了避免同步能解析、流式或异步仍然只返回文本的行为分叉。保留现有客户端作为 A2A 传输适配器，归一化逻辑从协议事件处理逻辑中拆开，便于用纯单元测试覆盖。

备选方案是让每个 SubAgent 返回已经序列化好的内部 Java 模型，但跨服务无法共享 Java 对象，且会把 Supervisor 的内部字段泄露到 A2A 服务端，故不采用。

### 2. 采用确定性的结构化内容优先级

归一化器按以下顺序选择业务结构化结果：

1. Artifact 中的 JSON/Data part；
2. Message 或 Artifact 中可解析为 JSON 对象的 Text part；
3. 普通文本作为 `summary`/`output` 保留。

多个 Artifact 或多个 part 的文本继续按到达顺序聚合，Artifact IDs 使用有序去重集合。结构化对象中的既有字段直接保留；`contentType`、`nextHints` 等字段按预定义类型读取，类型不匹配时保留原值并回退到空的可选字段，不因单个字段异常丢弃整个响应。

选择 Data 优先于 JSON 文本，是因为 Data part 已明确表达结构化语义；保留文本回退则兼容当前各 SubAgent 通过 ReactAgent `output` 生成 JSON 字符串的实现。

### 3. 保持本地 taskId，单独记录远端 taskId

`AgentTaskInvokeResponse.taskId` 继续表示 Supervisor 本地工作流 taskId，保证 checkpoint、恢复和权限链路不变。远端 A2A Task ID 放入结构化结果的保留字段 `remoteTaskId`，并同步写入终态事件 metadata 的 `childTaskId`；Artifact IDs、A2A 状态和错误信息也写入同一终态结果 envelope。

选择保留本地 taskId 而不是改用远端 taskId，是因为 Supervisor 的 GraphState 以本地 taskId 为主键，直接替换会造成状态恢复和事件查询错配。

### 4. 通过现有 SessionStreamEvent metadata 传递终态结果

终态会话事件在 metadata 中增加一个结构化 `result` envelope，至少包含：

- `status`
- `remoteTaskId`
- `artifactIds`
- `structuredOutput`
- `nextHints`
- `summary`
- `error`（存在时）

`content` 仍然提供客户端展示用文本，生命周期字段和现有 `childRunId`、`parentTaskId`、`branchId` 继续保留。中间事件不增加完整结果。由于 metadata 已由 `SessionEventAuditService` 写入审计表并在 replay 时读取，终态结果自然具备断线重放能力。

备选方案是给 `SessionStreamEvent` 新增强类型 `response` 字段。该方案会修改 common DTO、所有构造器、审计读写和现有 SSE 客户端契约；在当前仅需要 Supervisor 侧增量兼容的场景下，metadata envelope 的改动面更小。后续若外部客户端需要稳定的强类型字段，可再单独版本化公共事件模型。

### 5. 由执行层统一发布终态结果

流式执行收到带有 `result` 的终端 `ChildAgentStreamEvent` 时，发布一次携带 `result` envelope 的终态 `SessionStreamEvent`；如果底层客户端没有发送终端事件，则在流正常返回后用最终 `AgentTaskInvokeResponse` 补发终态事件。同步阻塞和异步状态查询同样通过统一的终态发布方法携带结果。

发布逻辑必须保持幂等：已有终态事件不重复生成第二个不同结果，远端 Task 状态和 Artifact 集合以最终聚合结果为准。速率限制只影响增量事件，不得丢弃终态事件。

### 6. Supervisor 路由只消费归一化结果

Graph 路由、下一 Agent 上下文构建和产物持久化继续消费 `AgentTaskInvokeResponse`，不直接读取 A2A SDK 对象或 SSE metadata。归一化器保证 `nextHints`、领域字段和 Artifact 引用已在响应返回前就位；没有路由提示时按现有完成路径结束，失败或取消状态不继续 handoff。

这样可以把协议层变化限制在 agent-service 的 A2A adapter 和执行层，避免在 LangGraph 节点中加入 A2A 特有分支。

## Risks / Trade-offs

- [Risk] SubAgent 返回的 JSON 可能存在 Markdown 代码围栏、额外说明或字段类型不一致 → 归一化器先做受限的文本清理和 JSON 对象解析，解析失败保留原始文本，不把解析异常升级为无上下文失败；同时增加真实样例测试。
- [Risk] 终态结构化结果进入现有审计 metadata 后可能增大事件体 → 只在终态事件保存结构化结果，对结果内容执行已有大小限制并过滤 raw、reasoning、arguments 等敏感字段；超过限制时保留摘要、标识和状态。
- [Risk] 旧 SSE 客户端不识别 `metadata.result` → 旧客户端仍可使用原有 `content` 和生命周期字段，新客户端按可选字段读取 result；不改变事件类型和既有构造器。
- [Risk] A2A 流可能重复发送 Task/Artifact 终态 → 按远端 taskId、Artifact ID 和终态状态幂等聚合，最终只向会话流发布一个完成结果。
- [Risk] 仅在 Supervisor 侧兼容多个领域输出格式会掩盖 SubAgent 契约不一致 → 记录无法识别的结构化输出和 agentId，后续再通过独立变更统一各 SubAgent 的 JSON schema。

## Migration Plan

1. 先发布 Supervisor 侧归一化和终态 metadata envelope；没有新字段的旧 SubAgent 继续按普通文本兼容运行。
2. 部署后通过同步、流式和异步调用分别验证 `structuredOutput`、`remoteTaskId`、Artifact IDs、路由和 SSE 重放。
3. 若发现兼容性问题，可关闭终态 result envelope 发布，保留客户端已有文本事件和内部归一化回退；无需回滚数据库结构。
4. 后续再单独推进各 SubAgent 输出 JSON/Data Artifact 的统一契约。

