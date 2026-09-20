## Purpose

为 Supervisor 提供可路由、可追踪且可通过 SSE 交付的官方 A2A 子 Agent 最终响应，确保结构化业务结果不会在协议适配或流式转发过程中丢失。

## ADDED Requirements

### Requirement: Normalize official A2A child responses

系统 SHALL 将官方 A2A 返回的文本、JSON/Data 内容和 Artifact 内容统一转换为 Supervisor 可消费的标准子 Agent 响应。对于可解析的结构化对象，系统 SHALL 将对象字段作为结构化结果提供，而不是仅将整个对象作为普通字符串嵌套在单一文本字段中；同时 SHALL 保留原始文本或不可解析内容，避免信息丢失。

#### Scenario: Structured JSON text is normalized

- **WHEN** 子 Agent 通过 A2A 返回包含 `contentType`、业务结果字段或 `nextHints` 的合法 JSON 文本
- **THEN** Supervisor 获得包含这些字段的结构化响应，并可直接使用 `nextHints` 和业务结果字段进行后续路由和上下文构建

#### Scenario: JSON/Data Artifact is normalized

- **WHEN** 子 Agent 通过 A2A Artifact 返回 JSON/Data part
- **THEN** Supervisor SHALL 解析该 part 的结构化内容，并将其与 Artifact 标识关联到同一个最终响应中

#### Scenario: Plain text response remains compatible

- **WHEN** 子 Agent 返回无法解析为结构化对象的普通文本
- **THEN** Supervisor SHALL 保留该文本作为响应内容，生成可消费的标准响应，并将缺失的可选结构化字段置为空值而不是使整个调用失败

### Requirement: Preserve A2A task and artifact correlation

系统 SHALL 在子 Agent 响应从 A2A 协议层传递到 Supervisor 内部响应和会话流的过程中保留远端 A2A task 标识、Artifact 标识、终态状态以及错误信息。远端 task 标识 SHALL 与 Supervisor 本地请求标识同时可用，不能用本地标识覆盖远端标识。

#### Scenario: Completed task keeps remote identity

- **WHEN** A2A 返回已完成的远端 Task 及其 Artifact
- **THEN** Supervisor 的最终响应 SHALL 包含远端 Task 标识、所有已发现的 Artifact 标识和完成状态

#### Scenario: Failed task keeps error context

- **WHEN** A2A 返回失败或取消状态并附带错误信息
- **THEN** Supervisor SHALL 发布终态响应，保留远端 Task 标识、终态状态和错误信息，并停止继续路由该子 Agent

#### Scenario: Artifact update precedes task completion

- **WHEN** 流式过程中先收到一个或多个 Artifact 更新，之后才收到 Task 终态
- **THEN** 最终响应 SHALL 合并此前已收到的 Artifact 标识及内容，不得只使用最后一个事件的数据

### Requirement: Propagate terminal structured response through session streaming

系统 SHALL 将子 Agent 的最终标准响应透传到 Supervisor 会话流的终态事件中。终态事件 SHALL 同时保留面向用户的文本内容、可追踪元数据和结构化响应，客户端通过 SSE 订阅或断线重放均可获得相同的最终结果。中间增量事件不要求携带完整结构化响应，但不得伪造终态结果。

#### Scenario: Streaming completion carries final result

- **WHEN** 子 Agent 流式调用正常结束并已生成标准结构化响应
- **THEN** Supervisor SHALL 发送一个可识别的终态会话事件，其中包含最终文本、结构化结果、远端 task 标识和 Artifact 标识

#### Scenario: Replay returns the terminal result

- **WHEN** 客户端在子 Agent 调用完成后重新订阅同一会话或从终态事件之前的位置重放事件
- **THEN** 客户端 SHALL 能够从重放数据中恢复与首次推送一致的最终结构化响应和关联信息

#### Scenario: Intermediate events do not terminate the stream

- **WHEN** 子 Agent 只返回进度、消息增量或 Artifact 更新而尚未返回终态
- **THEN** Supervisor SHALL 继续发布中间事件并保持会话可用，不得提前发布完成事件或触发最终路由

### Requirement: Use one response contract across execution modes

系统 SHALL 对同步调用、实时流式调用和异步状态查询使用一致的响应归一化和错误语义。相同的 A2A Task 和 Artifact 结果在不同调用模式下 SHALL 产生等价的结构化字段、关联标识、终态状态和错误信息。

#### Scenario: Synchronous and streaming results are equivalent

- **WHEN** 同一个子 Agent 分别通过同步请求和流式请求返回相同的 A2A Task 结果
- **THEN** Supervisor 获得的最终结构化业务字段、远端 task 标识、Artifact 标识和终态状态 SHALL 等价

#### Scenario: Async status query completes normalization

- **WHEN** 异步调用在初始请求后通过状态查询获得最终 Task
- **THEN** 状态查询结果 SHALL 使用与初始同步或流式调用相同的解析规则，并可被 Supervisor 继续消费

### Requirement: Route Supervisor using normalized child results

Supervisor SHALL 使用归一化后的子 Agent 响应进行下一步上下文构建、产物持久化和 handoff 路由。结构化响应中的 `nextHints` 或等价路由信息 SHALL 能够决定继续调用目标；没有有效下一步目标时，系统 SHALL 正常进入完成路径。

#### Scenario: Valid next hint continues handoff

- **WHEN** 子 Agent 最终响应包含有效的下一 Agent 提示或目标
- **THEN** Supervisor SHALL 将归一化响应写入当前任务上下文，并按照该目标继续执行 handoff，而不是因原始响应被包装为文本而提前完成

#### Scenario: Domain fields reach downstream nodes

- **WHEN** 子 Agent 响应包含内容类型、业务对象、媒体任务或交易决策等结构化字段
- **THEN** 后续上下文构建和产物持久化 SHALL 能够读取这些字段并执行对应处理

#### Scenario: Missing route hint completes safely

- **WHEN** 子 Agent 响应合法但不包含下一步目标
- **THEN** Supervisor SHALL 保留最终响应并进入完成路径，不得因为缺少可选路由信息而报错
