## Context

当前 8 个领域 Provider 都以相同模式构建 `ReactAgent`，设置 `outputKey("output")` 和 `A2aSupervisorContextInterceptor`，再由 `spring-ai-alibaba-starter-a2a-nacos` 自动创建官方 A2A 服务端。Starter 的 `GraphAgentExecutor` 有两个与目标契约不匹配的行为：它只拼接请求中的 TextPart，忽略 DataPart；非流式结果和流式中间结果都以 TextPart Artifact 发布，无法产生真正的 DataPart 终态。与此同时，8 份 Nacos Agent Card 已声明 `application/json` 和 streaming，形成了声明与实现不一致。

Supervisor 侧的 `OfficialA2aResponseNormalizer` 已经能够优先读取 DataPart、兼容 JSON 文本并保留 Artifact ID、远端 taskId、状态和 `nextHints`。本变更只负责让 SubAgent 按该能力稳定地产出结果，不修改 Supervisor 归一化和 SSE 终态链路。

## Goals / Non-Goals

**Goals:**

- 在 A2A 服务端统一解析 TextPart、DataPart、metadata 和 Supervisor 上下文。
- 为所有官方 SubAgent 生成统一的结构化成功结果、稳定终态 Artifact 和真实任务状态。
- 保持同步、流式和任务查询看到同一业务结果语义。
- 让 Agent Card 的输入/输出模式、streaming 能力和领域技能与实际服务一致。
- 通过公共适配器消除 8 个 Provider 的协议实现分叉，并保留各领域自己的工具和 Prompt。

**Non-Goals:**

- 不替换 Alibaba A2A SDK、JSON-RPC/SSE 传输或 LangGraph/ReactAgent 执行框架。
- 不修改 Supervisor 的 `OfficialA2aAgentClient`、响应归一化器、Graph 路由或公共 SSE DTO。
- 不把所有领域业务字段强行合并成一个 Java 领域模型；公共层只约束通用 envelope，领域字段仍由各服务定义。
- 不在本变更中改造非官方 A2A Provider 或旧的 Dubbo/HTTP 业务接口。

## Decisions

### 1. 在项目侧接管 Starter 默认 AgentExecutor

每个官方 A2A 服务通过项目侧配置提供自己的 `AgentExecutor` Bean，使 Starter 的 `@ConditionalOnMissingBean` 不再创建 `GraphAgentExecutor`。Executor 由公共 A2A 支持代码实现，接收根 `ReactAgent`、ObjectMapper 和领域输出策略；8 个 Provider 只负责注入自己的 ReactAgent、上下文拦截器和领域配置。

这样可以修复 Starter 默认 Executor 对 DataPart、终态 Artifact 和错误状态的限制，同时不需要修改第三方依赖。公共适配器应放在可被 8 个服务复用的公共 A2A 支持模块中；若现有 `common` 不适合引入 Spring AI/A2A 依赖，则新增轻量 `common-a2a` Maven 模块，并由 8 个服务依赖。

### 2. 采用“文本增量 + 结构化终态 DataPart”

流式期间继续发送 TextPart 增量，兼容当前客户端的展示和进度体验；执行结束后对聚合输出做一次受限 JSON 归一化，并用一个稳定的终态 Artifact 携带 DataPart。Supervisor 已经会收集 Artifact parts 并优先解析 DataPart，因此同步响应、流式终态和异步查询可以收敛到同一结构化结果。

默认成功结果的顶层形状为：

```json
{
  "contentType": "listing",
  "summary": "可展示的简短结论",
  "nextHints": [],
  "<domainField>": "<domainValue>"
}
```

`contentType` 由领域输出策略固定，`summary` 和 `nextHints` 由结果校验器补齐，领域字段不移动到 `payload` 内，避免破坏现有 Supervisor 路由和持久化消费。

### 3. 输入与上下文采用协议层显式映射

Executor 读取所有 TextPart 并按请求顺序合并为指令，读取 DataPart 为结构化输入；DataPart 不直接拼接成不可读的 `toString()`，而是以受限 JSON 序列化结果注入 Agent 输入或 RunnableConfig。顶层 `threadId`、`isStreaming` 和 `supervisor` metadata 继续进入 `RunnableConfig`，现有 `A2aSupervisorContextInterceptor` 继续负责模型上下文注入，并统一扩展为结构化渲染 `supervisor` 中的 session/task/trace、约束和 structuredContext。

输入解析会限制大小和字段范围，不把未经筛选的 metadata 全量放入 Prompt；内部 correlation 字段只供执行关联，不作为用户可见业务结果自动返回。

### 4. 结构化结果由适配器校验和序列化

适配器负责移除外围代码围栏、解析 JSON object、补齐通用 envelope、校验 `nextHints` 类型和过滤敏感字段，然后生成 DataPart。合法的领域 JSON 直接保留未知字段，便于不同 SubAgent 扩展；不合法时根据请求输出策略返回明确失败，避免完成状态携带无法消费的假结构。

显式文本输出仍允许返回 TextPart，但必须将结果模式标记为 text；默认 Supervisor 领域调用按 JSON 策略执行。该策略保留向后兼容，同时不再让 Agent Card 宣称 JSON 却只返回未标记的普通文本。

### 5. 终态 Artifact 使用稳定标识且与进度隔离

进度 Artifact 使用带序号的非终态 ID；业务终态 Artifact 使用由 agentId、远端 taskId 和结果语义组成的稳定 ID，例如 `agentId:taskId:result`，不写入原始 Prompt 或敏感数据。重复状态更新只允许复用同一终态语义，不重复生成竞争结果。

同步执行直接发布终态 Artifact；流式执行在每个增量结束后聚合相同的完整文本并发布终态 Artifact；异步查询依赖 A2A TaskStore 保存的同一 Task/Artifact。这样 Supervisor 轮询和 SSE 流式接收得到一致的 DataPart。

### 6. 任务失败优先于文本兜底

模型、工具、输入解析或 JSON 校验失败时，Executor 发布失败 Task 状态和安全错误消息，不先发布 completed 再补失败。错误消息包含稳定类别，如 `INVALID_STRUCTURED_OUTPUT`、`TOOL_EXECUTION_FAILED`、`EXECUTION_FAILED`，不包含完整堆栈、密钥、Prompt、工具参数和 reasoning。

## Affected Components

- 公共 A2A 支持模块：请求 Part 解析、结构化结果策略、终态 Artifact 生成、错误映射和 `AgentExecutor`。
- 8 个 `*OfficialA2aAgent`：接入公共 Executor/输出策略，保持领域模型、工具和上下文拦截器不变。
- 8 个 Nacos 服务配置：校准 card 的输入/输出模式、streaming 和 skill 声明，保持官方端点不变。
- 各领域测试与 `agent-service` 配置/协议测试：覆盖注册、运行时响应和端到端兼容性。

## Risks / Trade-offs

- [Risk] 第三方 A2A SDK 类型或 TaskUpdater 行为变化 → 将 SDK 调用隔离在公共 Executor，使用固定版本 API 做单元测试，并保留 Starter 默认行为所需的 Bean 接口。
- [Risk] 严格 JSON 校验会暴露原先被文本兜底隐藏的 Prompt 输出问题 → 为 8 个领域 Prompt 增加明确 JSON 输出指令和样例，错误时返回可诊断但脱敏的失败类别。
- [Risk] DataPart 和终态 Artifact 增加事件体积 → 限制结构化输入/输出大小，只保存终态完整数据，增量事件只携带展示文本。
- [Risk] 公共模块引入 A2A 依赖扩大构建范围 → 优先新增隔离的 `common-a2a` 模块，避免把协议 SDK 传递到不需要 A2A 的基础模块。

## Migration Plan

1. 先加入公共 Executor 和纯协议/结果校验测试，再逐个接入 8 个官方 Provider。
2. 同步更新 8 份 Agent Card 和领域 Prompt，保持 `/a2a` 与 Agent Card URL 不变。
3. 以同步、流式、异步三类请求验证 DataPart、TextPart、终态状态、Artifact ID 和 Supervisor 路由。
4. 灰度期间如某领域暂不能稳定生成 JSON，可将该领域显式配置为 text 模式，不影响其他 Agent；修复 Prompt/输出策略后再切回 JSON。
5. 完成测试和配置校验后提交并推送；不需要数据库迁移或 Supervisor 回滚。
