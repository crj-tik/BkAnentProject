# Distributed Multi-Agent Delivery Status

## 1. Scope

This document summarizes current implementation status against:

- `distributed-multi-agent-a2a-plan.md`
- `distributed-multi-agent-implementation-v2.md`
- `distributed-multi-agent-implementation-v2_1.md`
- `distributed-multi-agent-midterm-plan.md`
- `distributed-multi-agent-next-phase-backlog.md`

Status labels:

- `DONE`: implemented and verified in code
- `PARTIAL`: implemented in usable form but not fully aligned with target shape
- `TODO`: not implemented or only documented

## 2. Core Architecture Status

### 2.1 Supervisor / Graph / A2A

- `DONE` official Spring AI Alibaba Graph has replaced the earlier custom-only orchestration core in `agent-service`
- `DONE` official graph coverage includes planning, single-agent invoke, parallel invoke, approval entry, approval decision write-back, resume flow, handoff flow
- `PARTIAL` A2A sync / async / stream three-mode protocol is present; `listing / marketing / media / trade / contract / settlement / notification` now all have official Spring AI Alibaba A2A server/card pilots, and supervisor main-chain invoke already prefers the official path for these agents, but legacy `/internal/a2a/*` compatibility endpoints still remain in repository
- `DONE` supervisor main-chain routing no longer falls back to `HttpA2aAgentClient` for migrated agents; this is locked by `DelegatingA2aAgentClientTest`
- `DONE` legacy `/internal/a2a/*` controllers have been physically removed from migrated agent modules; supervisor config now uses `/a2a` semantics for migrated agents
- `DONE` supervisor async task and async workflow management endpoints are present
- `PARTIAL` distributed agent registry supports `Nacos + AgentCard` discovery with static fallback; all current domain agents now expose official Spring AI Alibaba A2A server/card pilots and supervisor official invoke path, but discovery still keeps static fallback and runtime still preserves legacy compatibility routes

### 2.2 Domain Agents

- `DONE` `listing-agent`
- `DONE` `marketing-agent`
- `DONE` `media-agent`
- `DONE` `trade-agent`
- `DONE` `contract-agent`
- `DONE` `settlement-agent`
- `DONE` `notification-agent`

### 2.3 Shared Infrastructure

- `DONE` `memory-service` supports session shared memory
- `DONE` `memory-service` supports task artifact memory
- `DONE` `memory-service` supports handoff relation memory
- `DONE` RocketMQ-based session event bus is present
- `DONE` diagnostics and event-audit query surfaces are present

## 3. Business Flow Status

### 3.1 Approval and Resume

- `DONE` approval is centralized in supervisor
- `DONE` approval callback supports approved / rejected / terminated
- `DONE` approval resume action is graph-driven

### 3.2 Parallel / Handoff

- `DONE` listing + trade parallel invoke skeleton
- `DONE` explicit route decision after parallel merge
- `DONE` handoff history and handoff relation persistence

### 3.3 Marketing Publish Chain

- `DONE` `marketing.generate_copy`
- `DONE` `media.generate_video_task`
- `DONE` `marketing.publish_prepare`
- `DONE` `marketing.publish`
- `DONE` `notification.send`
- `DONE` minimum `marketing -> media -> publish_prepare -> publish -> notification` closed loop

## 4. Midterm Plan Status

### 4.1 Phase 1: Protocol Convergence

- `DONE` `AgentCard` capability flags have been aligned with implemented async/stream endpoints
- `DONE` A2A paths are explicit, not inferred by string replacement
- `PARTIAL` protocol constraints are enforced by code and convention, but there is not yet a single generated contract reference artifact

### 4.2 Phase 2: Artifactization

- `DONE` companion artifacts exist for copy draft, publish payload, media task detail, contract review detail, settlement detail
- `DONE` downstream context now prefers artifact references over large raw payloads
- `PARTIAL` some business outputs still keep compact raw fields in `structuredOutput` for compatibility
- `TODO` a stricter artifact schema registry / enum governance layer is not yet formalized

### 4.3 Phase 3: Permissions and Boundaries

- `DONE` workflow read and artifact read checks are enforced with owner-aware validation
- `DONE` async task / async workflow management now requires `userId` and owner validation
- `DONE` child-agent invoke permission check exists before supervisor -> child calls
- `DONE` MCP and RAG entry permissions are gated
- `DONE` handoff context is minimized compared with earlier versions
- `PARTIAL` permission model is still service-level and request-level; tenant-wide data isolation is not yet complete

### 4.4 Phase 4: Event-Driven Upgrade

- `DONE` notification-service consumes RocketMQ workflow events
- `DONE` event-first, handoff-fallback behavior exists for notification path
- `DONE` notification consume dedupe, failure state, dead-letter state, and query surface exist
- `PARTIAL` notification still uses local retry/dead-letter semantics inside service, not a fully externalized MQ retry / DLQ governance chain

### 4.5 Phase 5: Workflow Async Formalization

- `DONE` workflow async create / status / stream
- `DONE` workflow async cancel
- `DONE` workflow async retry / replay
- `DONE` session stream bridge is used for workflow async stream
- `PARTIAL` async execution is still mixed between local async orchestration and child-agent async proxy depending on path, which is acceptable but not yet fully unified

### 4.6 Phase 6: Observability and Governance

- `DONE` diagnostics query by `taskId / traceId / approvalId / artifactId / asyncTaskId / asyncWorkflowId / grayStrategyVersion`
- `DONE` event-audit query surface
- `DONE` graph / async / security / notification metrics
- `DONE` graph subgraph audit events
- `DONE` gateway-level rate limiting
- `DONE` agent-service internal rate limiting
- `DONE` gray-release context injection and `strategyVersion` down-sink
- `DONE` Prometheus actuator exposure for `agent-service` and `notification-service`
- `DONE` operations governance API for runtime rate-limit override and gray-release override
- `DONE` event-audit retention and archive window
- `PARTIAL` metrics are exposed and documented, but Grafana dashboards are still baseline docs rather than checked-in dashboard JSON

## 5. Next-Phase Backlog Status

### 5.1 P0

- `DONE` Redis-capable `SupervisorRateLimiter`
- `DONE` gateway rate limiting
- `DONE` load test scripts and baseline docs
- `DONE` grayStrategyVersion query support

### 5.2 P1

- `DONE` permissions continued to sink into async management and workflow/artifact access
- `DONE` artifact governance tightened further in handoff and downstream context
- `DONE` notification event consume enhancement with retry cap, dead-letter state, and query filters

### 5.3 P2

- `DONE` Prometheus exposure and baseline Grafana / alert guide
- `DONE` graph strategy versioning for versioned preferred agents and route overrides
- `DONE` operations governance baseline for runtime overrides and event-audit retention
- `PARTIAL` operational governance is in-memory at runtime; persistent control-plane storage and multi-instance coordination are still missing

## 6. Remaining Gaps

### 6.1 Important Partial Items

- `PARTIAL` A2A runtime is now descriptor-driven: supervisor main chain prefers official Alibaba A2A by `runtimeProvider/runtimeType`, but non-migrated agents can still retain custom runtime
- `DONE` legacy `/internal/a2a/*` compatibility endpoints are no longer present in migrated child services
- `DONE` Spring AI Alibaba official A2A discovery and card exposure are now the primary source for migrated agents
  - child services publish `agent-id/domain/runtime/payload-mode` into Nacos metadata
  - `agent-service` static `agent.distributed.agents` has been reduced to an empty override container by default
  - supervisor catalog can be inspected through the discovered descriptor view
  - `strict Nacos catalog` mode is enabled by default, so undiscovered agents are no longer silently seeded from local static config
- `DONE` runtime / discovery / governance boundaries are cleaner now
  - `runtime` is selected from `RegisteredAgentDescriptor.runtimeType`
  - `discovery` produces descriptors with source/runtime/payload metadata
  - `governance` only provides overrides; default agent routing has been split out
- `PARTIAL` artifact governance still preserves some compact raw fields for compatibility
- `PARTIAL` event audit archive is in-memory, not persisted
- `DONE` runtime governance overrides are persisted in `agent_governance_override`
- `PARTIAL` Prometheus/Grafana integration is documented and exposed, but ready-made dashboard import artifacts are not committed

### 6.2 Major TODO Items

- `TODO` complete A2A officialization on Spring AI Alibaba route only
- `TODO` finish A2A officialization cleanup after pilot rollout
  - official dependency `spring-ai-alibaba-starter-a2a-nacos` has been verified and adopted in migrated agent modules
  - `listing / marketing / media / trade / contract / settlement / notification` already expose official A2A server/card pilots
  - `agent-service` already prefers official invoke path for these migrated agents
  - current repository code has already retired migrated child-agent `/internal/a2a/*` compatibility controllers from the main implementation
  - remaining work is concentrated on deeper runtime/discovery cleanup rather than old child controller retirement
- `DONE` persistent control plane for runtime governance overrides
- `DONE` persisted event-audit archive and retention are backed by `agent_event_audit`
- `PARTIAL` async/workflow runtime state is persisted in `agent_async_task` and `agent_async_workflow`, but execution workers are still local process executors rather than a fully externalized runtime
- `TODO` stricter artifact schema catalog / registry
- `TODO` tenant-grade isolation and broader data-scope controls
- `TODO` externalized MQ dead-letter / retry governance beyond local notification-service policy
- `TODO` checked-in Grafana dashboard JSON and alert rule files

## 7. Practical Conclusion

Current project state is no longer “prototype only”.

It already has:

- distributed supervisor + domain agents
- official Spring AI Alibaba Graph orchestration
- approval / parallel / handoff / resume
- async task and async workflow management
- event-driven notification
- diagnostics, audit, metrics, rate-limit, gray-release, governance basics

The next stage should not focus on adding more agent types first.

Recommended priority now:

1. Externalize async/workflow execution workers beyond local process executors
2. Tighten remaining artifact and tenant isolation rules
3. Turn observability docs into deployable dashboard / alert assets
4. Expand production-grade MQ retry / dead-letter governance
