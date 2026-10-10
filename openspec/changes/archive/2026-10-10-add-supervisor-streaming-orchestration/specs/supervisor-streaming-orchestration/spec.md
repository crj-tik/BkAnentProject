## Purpose

为 Supervisor 提供统一、可恢复的多 Agent 流式编排能力，使客户端在子 Agent 执行、切换、并行、审批等待和恢复期间始终使用同一条会话流，并能获得有序、可追踪的事件。

## ADDED Requirements

### Requirement: Supervisor SHALL expose one logical stream for a session

Supervisor SHALL provide a logical stream keyed by `sessionId` for a workflow execution. The client SHALL NOT need to establish a new stream when execution moves from one child Agent to another, enters an approval wait, or resumes from a checkpoint.

#### Scenario: Child Agent handoff keeps the client stream

- **WHEN** the Supervisor completes one child Agent and routes the workflow to another child Agent
- **THEN** events for both Agents SHALL be published to the same logical session stream
- **AND** the event identity SHALL identify the current Agent without requiring a new client connection

#### Scenario: Workflow waits for approval

- **WHEN** the workflow reaches a user approval point
- **THEN** the stream SHALL publish an approval-wait event containing the workflow and approval correlation information
- **AND** the workflow SHALL stop protected downstream execution until a valid decision is received

### Requirement: Stream events SHALL have a stable and traceable envelope

Every externally visible Supervisor stream event SHALL contain, either as typed fields or equivalent serialized metadata, a stable event identifier, a monotonically increasing sequence within the logical stream, `sessionId`, `taskId`, `traceId`, `agentId` when applicable, an event type, and an event timestamp. Child execution events SHALL additionally identify the child run or branch when one exists.

#### Scenario: Events from sequential child Agents are correlated

- **WHEN** a workflow invokes Agent A and then Agent B
- **THEN** the events SHALL preserve the same session and workflow correlation identifiers
- **AND** the events SHALL distinguish Agent A from Agent B
- **AND** the sequence SHALL allow the client to render the transition in the order it occurred

#### Scenario: Events from parallel branches are distinguishable

- **WHEN** the Supervisor invokes multiple child Agents in parallel
- **THEN** each branch event SHALL include an Agent or branch identity
- **AND** the client SHALL be able to associate each event with its branch without relying on arrival order alone

### Requirement: Supervisor SHALL relay safe child execution events

When a child Agent or child graph supports streaming, Supervisor SHALL relay supported output deltas, tool progress, lifecycle transitions, and terminal results into the session stream. Relayed events SHALL preserve the originating Agent identity and SHALL NOT expose private reasoning or other content that is not designated as user-visible.

#### Scenario: Streaming child Agent produces output deltas

- **WHEN** a child Agent emits a user-visible output delta
- **THEN** Supervisor SHALL publish a corresponding delta event on the session stream
- **AND** the delta event SHALL be associated with the active child run
- **AND** the final structured child result SHALL be published separately or be identifiable as terminal

#### Scenario: Child Agent does not support streaming

- **WHEN** a child Agent cannot provide a streaming response
- **THEN** Supervisor SHALL fall back to lifecycle or progress events and the final child result
- **AND** the workflow SHALL remain routable using the final structured result
- **AND** Supervisor SHALL NOT fabricate token-level events

### Requirement: Stream events SHALL be observational and Graph state SHALL remain authoritative

Token deltas and transient progress events SHALL NOT be required as Graph state updates and SHALL NOT independently trigger routing, handoff, completion, or approval decisions. Supervisor SHALL make those decisions from completed structured results, persisted artifacts, workflow state, and validated control inputs.

#### Scenario: Partial output cannot trigger a handoff

- **WHEN** a child Agent has emitted partial output but has not emitted a terminal result
- **THEN** Supervisor SHALL NOT route to the next Agent based only on the partial output
- **AND** routing SHALL occur only after a valid completed result is available

#### Scenario: Client reconnects during child execution

- **WHEN** the client disconnects after receiving some deltas and reconnects
- **THEN** the workflow execution and Graph state SHALL continue independently of the client connection
- **AND** the client SHALL be able to recover events from the last acknowledged sequence according to the replay policy

### Requirement: Supervisor SHALL pass explicit structured context across Agent transitions

For every child invocation, Supervisor SHALL pass a validated request context containing the active workflow correlation identifiers, target capability or intent, relevant structured outputs, artifact references, applicable constraints, and stream preference. Supervisor SHALL preserve the final child result and artifact references for subsequent routing and handoff.

#### Scenario: Downstream Agent receives upstream result

- **WHEN** Agent A produces a structured result and the workflow routes to Agent B
- **THEN** Agent B SHALL receive the relevant structured context and artifact references
- **AND** Agent B SHALL not need to reconstruct the upstream result from token deltas

#### Scenario: Invalid or incomplete handoff context

- **WHEN** a handoff lacks the required workflow correlation or target information
- **THEN** Supervisor SHALL publish a diagnosable workflow error
- **AND** Supervisor SHALL NOT invoke the downstream Agent

### Requirement: Stream delivery SHALL support replay and duplicate-safe consumption

Supervisor SHALL retain sufficient event history to bridge the gap between task acceptance and stream subscription and to replay events after a client reconnects. Replay SHALL be bounded by configured retention and SHALL use event identity or sequence so clients can discard duplicates safely.

#### Scenario: Subscription starts after task acceptance

- **WHEN** the workflow publishes events before the client establishes the stream
- **THEN** a later subscription for the same session and task SHALL replay retained events from the requested position
- **AND** the client SHALL receive a deterministic transition from the replayed history into live events

#### Scenario: Reconnect requests an already delivered position

- **WHEN** the client reconnects with the last event identifier or sequence it processed
- **THEN** Supervisor SHALL replay only events after that position when retained
- **AND** duplicate delivery of an event SHALL be detectable by its stable identity

### Requirement: Existing non-streaming execution contracts SHALL remain compatible

The existing synchronous and asynchronous Supervisor task/workflow operations, approval callbacks, handoff routing, parallel aggregation, and final response contracts SHALL remain usable. Streaming support SHALL be additive, and unsupported child streaming SHALL use the existing final-result or status fallback behavior.

#### Scenario: Existing synchronous request remains valid

- **WHEN** a caller uses the existing synchronous Supervisor operation without requesting streaming
- **THEN** Supervisor SHALL return the existing final response contract
- **AND** the workflow SHALL not require a child Agent streaming implementation

#### Scenario: Existing asynchronous task remains observable

- **WHEN** a caller uses the existing asynchronous task or workflow operation
- **THEN** status and terminal result behavior SHALL remain compatible
- **AND** the session stream, when subscribed, SHALL expose the corresponding lifecycle events without changing task semantics
