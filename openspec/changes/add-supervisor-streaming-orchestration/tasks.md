## 1. Define the Supervisor stream contract

- [x] 1.1 Add the version-compatible stream event envelope fields for `eventId`, `sequence`, `childRunId`, `parentTaskId`, `branchId`, `phase`, `terminal`, and `visibility`, while preserving existing `SessionStreamEvent` construction and serialization behavior.
- [x] 1.2 Define the allowlisted workflow, Agent lifecycle, delta, tool-progress, handoff, approval, terminal, and failure event types, including which visibility values may reach the external SSE.
- [x] 1.3 Add child-run and branch correlation rules to Supervisor request construction without requiring changes to subagent business code or existing final-result DTO consumers.

## 2. Add ordered event persistence and replay

- [x] 2.1 Add the database migration and entity/mapper fields needed to persist a stable event ID and an ordered session/task sequence with idempotency support.
- [x] 2.2 Implement a Supervisor event envelope/sequence allocator that assigns identifiers before live publication and prevents duplicate event persistence for the same event ID.
- [x] 2.3 Extend event-audit queries to filter by session/task and replay events after an event ID or sequence in ascending order, while retaining current diagnostic query compatibility.
- [x] 2.4 Define bounded retention and overflow behavior for persisted replay events and the local in-memory fallback, with configuration defaults suitable for development.

## 3. Make the Supervisor stream resumable

- [x] 3.1 Update the session stream service so new events use the normalized envelope and are delivered consistently through local and RocketMQ-backed buses.
- [x] 3.2 Update SSE subscription handling to read `Last-Event-ID` or an explicit replay cursor, replay retained events, and atomically join the live subscriber without a replay-to-live gap.
- [x] 3.3 Emit the stable event identifier through the SSE `id` field and add heartbeat/cleanup behavior for long-running approval and child-agent executions.
- [x] 3.4 Preserve the existing task/workflow stream endpoints and add optional task/cursor parameters without breaking callers that only provide `sessionId`.

## 4. Implement the Supervisor child-event bridge

- [x] 4.1 Introduce an internal child-agent event model and bridge that normalizes remote A2A events and local child-graph events into Supervisor stream events.
- [x] 4.2 Add a stream-capable operation to the Supervisor A2A client abstraction and implement the remote streaming adapter using the configured Agent capability and stream path.
- [x] 4.3 Update A2A execution to publish child start, safe deltas, tool/progress events, terminal result, and failure events while accumulating the final `AgentTaskInvokeResponse` for Graph execution.
- [x] 4.4 Keep blocking invocation and async status polling as capability-based fallbacks; when a child does not stream, publish lifecycle/progress events only and never fabricate token deltas.
- [x] 4.5 Add visibility filtering and content-size/rate controls so private reasoning, unsafe tool parameters, and unbounded delta bursts do not reach external SSE.

## 5. Integrate streaming with Graph transitions and context passing

- [x] 5.1 Change the local single-agent execution path to expose child graph stream events through the common bridge while writing only the final structured response and artifacts into Graph state.
- [x] 5.2 Generate and propagate `childRunId`, `parentTaskId`, and `branchId` for sequential handoff and parallel branches while retaining the root `sessionId`, `taskId`, and `traceId`.
- [x] 5.3 Verify handoff events are emitted in order around route selection and downstream invocation, and that downstream requests contain only validated structured context, artifact references, constraints, and stream preference.
- [x] 5.4 Ensure partial deltas cannot trigger routing, approval, artifact persistence, or completion; only terminal structured child results may advance the Graph.
- [x] 5.5 Preserve approval pause/resume, parallel fan-out/fan-in, regeneration, cancellation, and final completion behavior while publishing the corresponding stream lifecycle events.
- [x] 5.6 Separate client disconnect from explicit workflow cancellation and define Supervisor-side cancellation propagation/fallback for currently non-cancelable subagent tasks.

## 6. Verify compatibility and failure recovery

- [x] 6.1 Add unit tests for event envelope compatibility, sequence allocation, duplicate suppression, visibility filtering, delta accumulation, and unsupported-stream fallback.
- [x] 6.2 Add integration tests for a sequential Agent handoff that assert one session stream, ordered Agent identities, structured context transfer, and final Graph routing.
- [x] 6.3 Add integration tests for interleaved parallel branches that assert branch correlation and aggregate completion without relying on arrival order.
- [x] 6.4 Add integration tests for approval waiting/resume, client disconnect/reconnect replay, subscription-after-acceptance, and terminal event delivery.
- [x] 6.5 Add regression tests proving existing synchronous requests, async status polling, A2A final responses, and approval callbacks remain compatible when streaming is disabled.
- [x] 6.6 Run the agent-service test suite and the relevant Maven compile/package checks, then document the Supervisor-only rollout switch and the later subagent adoption path.
