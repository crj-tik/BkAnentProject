## Purpose

本能力定义领域 SubAgent 作为官方 A2A 服务端时的输入、输出和任务生命周期契约，使 Supervisor 能够在同步、流式和异步场景下稳定消费结构化结果、Artifact 关联和错误状态。

## ADDED Requirements

### Requirement: SubAgent SHALL parse official A2A input parts and preserve Supervisor context

官方 A2A 服务端 SHALL 支持读取请求 Message 中的 `TextPart` 和 `DataPart`。TextPart SHALL 作为用户指令参与 Agent 执行，DataPart SHALL 作为结构化业务上下文参与 Agent 执行；请求 metadata 中的 `threadId`、`isStreaming` 和 `supervisor` 命名空间 SHALL 进入 ReactAgent 的 RunnableConfig/模型上下文。服务端 SHALL 保留关联字段的语义，不得把远端 A2A taskId 当作 Supervisor 本地 taskId。

#### Scenario: Text and Data input are combined

- **WHEN** Supervisor sends a text instruction and a DataPart containing structured context
- **THEN** the SubAgent SHALL execute with both values available
- **AND** the DataPart SHALL not be silently discarded or converted to an opaque protocol error

#### Scenario: Supervisor metadata is available to the model context

- **WHEN** an A2A request contains `supervisor.sessionId`, `supervisor.taskId`, `supervisor.traceId`, constraints, or structuredContext
- **THEN** the SubAgent SHALL make the values available to its context interceptor and Agent execution
- **AND** user-facing output SHALL not expose internal correlation identifiers unless the requested business payload explicitly requires them

### Requirement: Successful structured SubAgent results SHALL use a canonical JSON object

对于声明支持 `application/json` 的官方 A2A Agent，成功终态 SHALL 返回一个可解析的 JSON object。该对象 SHALL 在顶层包含 `contentType`、`summary` 和 `nextHints` 字段；`nextHints` SHALL 是字符串数组，缺少后续路由时使用空数组。领域业务字段 SHALL 保持为同一顶层对象中的额外字段，不得仅嵌套在无法被 Supervisor 直接消费的字符串中。

#### Scenario: Structured result is returned as a DataPart

- **WHEN** ReactAgent 生成合法的结构化业务结果并且 A2A 请求允许 JSON 输出
- **THEN** the terminal Artifact SHALL contain a `DataPart` whose data is the canonical JSON object
- **AND** the Artifact MAY include a human-readable TextPart summary
- **AND** the Supervisor SHALL be able to read `contentType`, domain fields and `nextHints` without parsing a prose wrapper

#### Scenario: Model wraps JSON in a code fence

- **WHEN** the model returns the canonical JSON object inside a Markdown code fence
- **THEN** the SubAgent adapter SHALL remove only the surrounding fence and normalize the object before building the terminal DataPart
- **AND** it SHALL not include Markdown delimiters in the DataPart data

#### Scenario: Structured result cannot be parsed

- **WHEN** JSON output is required but the model result is not a JSON object after bounded normalization
- **THEN** the SubAgent SHALL finish with a failed A2A Task and an explicit output-format error
- **AND** it SHALL not report the task as completed with a misleading plain-text success Artifact

### Requirement: SubAgent SHALL publish a stable terminal Artifact

每次成功完成的 A2A Task SHALL 发布且仅发布一个可识别的业务终态 Artifact。终态 Artifact ID SHALL 在同一 Agent、同一远端 taskId 和同一结果语义下稳定可重建；Artifact SHALL 携带适当的名称、内容类型或 metadata，并 SHALL 保留 DataPart 中的完整结构化结果。流式进度 Artifact 不得覆盖终态 Artifact。

#### Scenario: Terminal Artifact can be correlated

- **WHEN** Supervisor receives a completed Task and later queries or replays the Task
- **THEN** the terminal Artifact ID and its structured parts SHALL remain available
- **AND** repeated status or stream events SHALL not create competing terminal results

#### Scenario: Text-only result is explicitly requested

- **WHEN** the request explicitly selects text output and does not require a structured JSON result
- **THEN** the SubAgent MAY return a TextPart terminal Artifact
- **AND** the Artifact metadata SHALL identify the result as text rather than claiming JSON output

### Requirement: Streaming execution SHALL separate progress from terminal result

当 Agent Card 声明支持 streaming 时，SubAgent SHALL emit intermediate progress or text delta events without marking the task terminal. Stream completion SHALL emit one final task state and the complete terminal Artifact; the final Artifact SHALL contain the same canonical result that synchronous invocation would return for the same request.

#### Scenario: Streaming result reaches terminal state

- **WHEN** Supervisor sends a streaming A2A request
- **THEN** the SubAgent SHALL emit zero or more non-terminal progress/delta events
- **AND** SHALL emit one completed terminal event containing the final structured Artifact
- **AND** the final event SHALL be distinguishable from intermediate text fragments

#### Scenario: Stream execution fails

- **WHEN** model, tool, parsing, or serialization execution fails
- **THEN** the SubAgent SHALL emit a failed terminal Task state with an error message
- **AND** it SHALL not emit a later completed terminal state for the same task

### Requirement: Task states and errors SHALL reflect actual execution

SubAgent SHALL map successful execution to `completed`, cancellation to `canceled`, and execution, tool, or output-contract failures to `failed` or another appropriate final A2A state. Error responses SHALL retain a stable machine-readable category and a safe human-readable message; they SHALL not contain prompts, secrets, tool arguments, or model reasoning.

#### Scenario: Cancellation is requested

- **WHEN** the A2A client cancels a running task
- **THEN** the SubAgent SHALL stop or cooperatively terminate execution
- **AND** SHALL publish `canceled` without publishing a successful terminal Artifact

#### Scenario: Tool execution fails

- **WHEN** a domain tool fails and the Agent cannot produce a valid business result
- **THEN** the SubAgent SHALL publish a failed task state
- **AND** the error SHALL identify the failure category without leaking sensitive tool input or internal stack traces

### Requirement: Agent Card SHALL match the implemented contract

每个官方 SubAgent Agent Card SHALL accurately advertise `text/plain` and `application/json` input/output modes as supported by the implementation, SHALL advertise streaming only when the custom Executor supports the progress-to-terminal lifecycle, and SHALL retain the correct domain skill, A2A endpoint and official runtime metadata。

#### Scenario: Supervisor discovers a SubAgent

- **WHEN** Supervisor reads the SubAgent Agent Card from `/.well-known/agent.json`
- **THEN** the card SHALL describe the same `/a2a` endpoint used by the server
- **AND** its output modes and streaming capability SHALL be consistent with actual responses
- **AND** the card SHALL contain the domain skill used for routing
