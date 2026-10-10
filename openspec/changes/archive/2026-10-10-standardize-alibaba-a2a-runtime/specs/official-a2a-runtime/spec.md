# Spec Delta

## Purpose

Provides one interoperable runtime boundary for Supervisor child agents by using Alibaba Cloud official A2A discovery and message, task, stream, and artifact semantics while keeping Supervisor-specific orchestration state internal.

## ADDED Requirements

### Requirement: Child agents use official A2A discovery

Supervisor SHALL register and resolve a child agent only when the agent exposes an Alibaba-compatible Agent Card and an official A2A endpoint. Agent registrations that identify a custom HTTP provider, omit an Agent Card, or cannot be resolved through the official discovery path SHALL be rejected with a clear configuration or discovery error.

#### Scenario: Official Agent Card registration succeeds

- **WHEN** a registration points to a reachable official Agent Card and declares the official A2A provider
- **THEN** Supervisor stores the normalized agent descriptor and makes the child agent eligible for invocation

#### Scenario: Custom provider registration is rejected

- **WHEN** a registration declares a custom HTTP provider or only exposes the former custom task endpoints
- **THEN** Supervisor rejects the registration and reports that Alibaba official A2A is required

#### Scenario: Missing or invalid Agent Card is rejected

- **WHEN** discovery cannot retrieve or validate the official Agent Card
- **THEN** Supervisor does not route work to the agent and returns a discovery error that identifies the agent

### Requirement: Official A2A wire semantics are used for invocation

Supervisor SHALL send child-agent work as official A2A message or task operations and SHALL interpret official A2A message, task, status, stream, and artifact results. Supervisor MUST NOT send its internal task-invocation request object as an HTTP request body or require an internal task-invocation response object as an HTTP response body.

#### Scenario: Structured context reaches the child agent

- **WHEN** Supervisor invokes a child agent with instruction, session or thread identity, trace identity, source and target identities, structured context, constraints, and expected output
- **THEN** the official A2A request carries the instruction as message content and the supported correlation and orchestration fields as A2A metadata without requiring a custom JSON envelope

#### Scenario: Official result is normalized internally

- **WHEN** an official A2A response contains text, structured output, task state, or artifacts
- **THEN** Supervisor converts those values into its internal result model for graph routing, persistence, and handoff while preserving task, session, trace, status, and artifact correlation where supplied

#### Scenario: Official error is surfaced consistently

- **WHEN** the child agent returns an A2A protocol error, failed task state, or an invalid response
- **THEN** Supervisor marks the internal invocation as failed, includes the remote error context, and does not interpret the error as a successful custom response

### Requirement: Streaming and asynchronous tasks follow official A2A behavior

Supervisor SHALL use the official A2A streaming operation when streaming is requested and SHALL use official A2A task creation and task retrieval semantics when asynchronous execution is requested. Streaming and asynchronous results MUST remain correlated to the originating session, task, trace, and idempotency context.

#### Scenario: Streaming invocation returns incremental events

- **WHEN** a caller requests streaming and the child Agent Card advertises streaming support
- **THEN** Supervisor opens the official A2A streaming operation, forwards incremental message or artifact events, and closes the stream with the terminal task outcome

#### Scenario: Streaming is unavailable

- **WHEN** a caller requests streaming but the Agent Card does not advertise streaming support
- **THEN** Supervisor returns a capability error or uses the documented non-streaming fallback without attempting a custom stream endpoint

#### Scenario: Asynchronous task is resumed

- **WHEN** a caller requests asynchronous execution
- **THEN** Supervisor creates or resumes the official A2A task, stores the returned task identity, and retrieves status and final artifacts through official task operations

### Requirement: Correlation and artifacts survive the runtime boundary

Supervisor SHALL preserve supported session, thread, task, parent-task, trace, idempotency, handoff, and artifact correlation across official A2A calls and internal graph transitions. Remote agents MAY omit fields that are not part of the official response; Supervisor MUST retain its originating values when safe and unambiguous.

#### Scenario: Handoff result is correlated to the parent graph

- **WHEN** a graph hands work to a child agent with a parent task and trace identity
- **THEN** the resulting internal response and persisted artifacts can be associated with the originating parent task and trace

#### Scenario: Returned artifact is persisted once

- **WHEN** an official A2A result contains an artifact and the same task is observed again during polling or stream completion
- **THEN** Supervisor deduplicates the artifact using its task or artifact identity and does not create duplicate persisted records

### Requirement: MCP behavior is unaffected by A2A standardization

This capability SHALL NOT change MCP transport, endpoint, tool discovery, or tool execution behavior. A2A-only changes MUST NOT make an MCP client or server depend on the removed custom HTTP A2A protocol.

#### Scenario: Existing MCP tool call remains valid

- **WHEN** an agent invokes a configured MCP tool after the A2A runtime change
- **THEN** the MCP call uses its existing configured transport and endpoint semantics
