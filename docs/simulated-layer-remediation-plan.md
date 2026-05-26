# Simulated Layer Remediation Plan

## 1. Purpose

This document lists the remaining simulated/custom layers in the current distributed multi-agent implementation and defines the remediation order.

The goal is not to rebuild everything again.  
The goal is to replace the most architecture-significant simulated layers with official framework-backed implementations where the original design expected them.

Priority levels:

- `P0`: architecture-critical, should be corrected first
- `P1`: important runtime and operational consistency gaps
- `P2`: secondary production-hardening gaps

## 2. Summary

Current implementation is already strong in:

- distributed domain agents
- official Spring AI Alibaba Graph core orchestration
- approval / parallel / handoff / resume flow
- memory-service / artifact / handoff relation
- event-driven notification
- observability / rate limit / gray release baseline

But several important layers are still custom simulations rather than official or fully-aligned framework usage.

Most important remaining simulated layers:

1. A2A protocol and client runtime
2. Nacos + AgentCard discovery loop
3. Async task / workflow runtime
4. A2A streaming
5. Event audit archive
6. Governance control plane
7. Notification retry / dead-letter
8. Graph peripheral runtime semantics

## 3. P0: Critical Remediation

### 3.1 A2A Runtime

Current simulated implementation:

- custom DTOs:
  - `AgentTaskInvokeRequest`
  - `AgentTaskInvokeResponse`
- custom HTTP client:
  - [HttpA2aAgentClient.java](/D:/project/BkAnentProject/BkAnentProject/agent-service/src/main/java/com/bkanent/agent/client/HttpA2aAgentClient.java:1)
- custom child endpoints:
  - `/internal/a2a/invoke`
  - `/internal/a2a/tasks`
  - `/internal/a2a/tasks/status`
  - `/internal/a2a/tasks/stream`

Why it is still simulated:

- protocol transport is project-defined, not framework-hosted
- child-agent runtime behavior is not managed by official A2A runtime
- error, task, and stream semantics are still local conventions

Target implementation:

- Spring AI Alibaba official Agent/A2A integration as the transport and runtime basis
- Nacos-discovered agents expose official A2A capability surface
- supervisor uses official A2A invocation path instead of raw `RestClient` orchestration
- do not introduce or retain `a2a-java-sdk-spec + custom controller` as a long-term parallel stack

Primary affected code:

- [HttpA2aAgentClient.java](/D:/project/BkAnentProject/BkAnentProject/agent-service/src/main/java/com/bkanent/agent/client/HttpA2aAgentClient.java:1)
- all `*AgentController.java` A2A endpoints
- `A2aExecutionService`
- `RegisteredAgentDescriptor`
- `AgentCard` wiring

Remediation order:

1. verify Spring AI Alibaba official A2A server/runtime dependency availability in build environment
2. introduce official A2A starter/runtime dependency, preferably Nacos-integrated starter
3. migrate one child agent first, recommended `listing-agent`
4. migrate supervisor invoke path
5. migrate remaining agents
6. remove fallback custom-only path after compatibility window

Current blocker:

- local Maven cache currently contains `spring-ai-alibaba-agent-framework`
- official documentation explicitly names `spring-ai-alibaba-starter-a2a-nacos` as the expected dependency for A2A + Nacos
- `spring-ai-alibaba-starter-a2a-nacos` has now been verified as resolvable and compilable in this repository
- `listing-master-service` now includes the official starter and exposes an official Spring AI Alibaba A2A server pilot
- `agent-service` discovery can now fetch `listing-agent` card from `/.well-known/agent.json`
- `agent-service` now routes `listing-agent` synchronous invoke through official Spring AI Alibaba remote A2A runtime
- `agent-service` now routes `listing-agent` async task create/status through official A2A client protocol
- `listing-agent` official streaming capability is now provided by the official A2A server endpoint rather than legacy `/internal/a2a/tasks/stream`
- `marketing-content-service` now includes the official starter and exposes an official Spring AI Alibaba A2A server pilot
- `agent-service` now routes `marketing-agent` `marketing.generate_copy`, `marketing.publish_prepare`, and `marketing.publish` through the official sync/async A2A client path
- `media-worker-service`, `business-service`, `contract-service`, `settlement-service`, and `notification-service` now also include the official starter and expose official Spring AI Alibaba A2A server/card pilots
- `agent-service` main-chain runtime now prefers official Alibaba A2A invoke for `listing / marketing / media / trade / contract / settlement / notification`
- therefore the remaining blocker is no longer child-agent rollout, but full removal of legacy `/internal/a2a/*` compatibility paths and completion of official runtime governance around them

Reference:

- Spring AI Alibaba A2A docs: `spring-ai-alibaba-starter-a2a-nacos`
- Spring AI Alibaba versions page: `spring-ai-alibaba-starter-a2a-nacos`

### 3.2 Nacos + AgentCard Discovery

Current status:

- partially remediated
- `agent-service` now supports `agent.distributed.a2a.discovery-provider=custom|official`
- official mode uses Spring AI Alibaba `RemoteAgentCardProvider`
- static registration is still retained as fallback, so this layer is not fully officialized yet
- this correction is limited to card discovery and does not yet mean A2A invoke/runtime has been officialized

Current simulated implementation:

- `DiscoveryClient` resolves service instance
- HTTP fetches `AgentCard`
- registry caches `RegisteredAgentDescriptor`
- static configuration remains the base source

Primary code:

- [DynamicAgentRegistry.java](/D:/project/BkAnentProject/BkAnentProject/agent-service/src/main/java/com/bkanent/agent/registry/DynamicAgentRegistry.java:1)

Why it is still simulated:

- registration is still configuration-led
- discovery loop is not an official agent runtime registry
- new agent onboarding still depends on predeclared local config

Target implementation:

- service instance registration via Nacos
- capability discovery via official Agent/A2A metadata lifecycle
- supervisor registry refreshed from discovery source rather than static map baseline

Remediation order:

1. keep current static fallback
2. add runtime-discovered descriptor source as first-class source
3. allow agents to self-register capability metadata
4. reduce static config to bootstrap/fallback only

### 3.3 Async Task / Workflow Runtime

Current simulated implementation:

- in-memory `ConcurrentMap`
- `CompletableFuture`
- local daemon thread pool

Primary code:

- [SupervisorAsyncTaskService.java](/D:/project/BkAnentProject/BkAnentProject/agent-service/src/main/java/com/bkanent/agent/service/SupervisorAsyncTaskService.java:1)
- [SupervisorAsyncWorkflowService.java](/D:/project/BkAnentProject/BkAnentProject/agent-service/src/main/java/com/bkanent/agent/service/SupervisorAsyncWorkflowService.java:1)

Why it is still simulated:

- restart loses runtime state
- multi-instance supervisor cannot share ownership consistently
- async execution control is still process-local

Target implementation:

- persisted async task/workflow runtime state
- resumable async execution lifecycle
- multi-instance safe management
- official or message-driven runtime semantics instead of local thread executor control

Remediation order:

1. introduce persistent async task/workflow tables
2. move status source of truth from memory to DB
3. replace local thread identity with resumable worker semantics
4. align stream/status API to persistent runtime

## 4. P1: Important Runtime Alignment

### 4.1 A2A Streaming

Current simulated implementation:

- session event bus + SSE bridge
- child agent async stream is exposed, but stream semantics are not unified by official A2A runtime

Primary code:

- [LocalSessionEventBus.java](/D:/project/BkAnentProject/BkAnentProject/agent-service/src/main/java/com/bkanent/agent/stream/LocalSessionEventBus.java:1)
- [RocketMqSessionEventBus.java](/D:/project/BkAnentProject/BkAnentProject/agent-service/src/main/java/com/bkanent/agent/stream/RocketMqSessionEventBus.java:1)

Target implementation:

- official A2A streaming semantics as first-class path
- session bus remains aggregation layer, not stream protocol substitute

### 4.2 Event Audit Archive

Current simulated implementation:

- active and archived audit are both in-memory deques

Primary code:

- [SessionEventAuditService.java](/D:/project/BkAnentProject/BkAnentProject/agent-service/src/main/java/com/bkanent/agent/stream/SessionEventAuditService.java:1)

Target implementation:

- persisted audit store
- retention policy and archive query independent of process lifetime

### 4.3 Governance Control Plane

Current simulated implementation:

- runtime overrides live in service memory only

Primary code:

- [SupervisorGovernanceService.java](/D:/project/BkAnentProject/BkAnentProject/agent-service/src/main/java/com/bkanent/agent/service/SupervisorGovernanceService.java:1)

Target implementation:

- persistent control-plane store for:
  - rate-limit overrides
  - gray-release overrides
  - strategy version switching

### 4.4 Notification Retry / Dead-Letter

Current simulated implementation:

- local attempt counter and business `DEAD_LETTER` state

Primary code:

- [NotificationWorkflowEventService.java](/D:/project/BkAnentProject/BkAnentProject/notification-service/src/main/java/com/bkanent/notification/service/NotificationWorkflowEventService.java:1)
- [NotificationEventConsumeServiceImpl.java](/D:/project/BkAnentProject/BkAnentProject/notification-service/src/main/java/com/bkanent/notification/service/impl/NotificationEventConsumeServiceImpl.java:1)

Target implementation:

- MQ-level retry / DLQ governance
- clearer separation between transport retry and business consume result

## 5. P2: Secondary Cleanup

### 5.1 Graph Peripheral Runtime

Current state:

- graph core is official
- but approval/async/governance/audit around it is still partly custom-managed

Target:

- shrink custom runtime shells around graph where framework capability exists

### 5.2 Artifact Governance Registry

Current state:

- artifact model is practical, but schema/type governance is still convention-driven

Target:

- formal artifact type registry and version policy

### 5.3 Dashboard / Alert Assets

Current state:

- docs exist
- importable dashboard/rule assets are not yet committed

Target:

- checked-in Grafana JSON and alerting rules

## 6. Recommended Execution Order

Recommended next execution order:

1. official A2A runtime migration
2. discovery loop correction
   - current repository has already split runtime/discovery/governance boundaries:
     - runtime is descriptor-driven instead of hardcoded migrated-agent lists
     - discovery emits `runtimeType/source/payloadMode` metadata
     - governance only contributes overrides, not default routing facts
   - migrated agents now publish identity and capability metadata into Nacos discovery
   - `agent-service` no longer keeps a full static migrated-agent catalog in local config
   - strict Nacos catalog mode is enabled by default; local config is no longer a hidden source of truth
3. A2A streaming correction
4. MQ retry / dead-letter formalization
5. async executor officialization / external runtime
6. secondary cleanup items

Current status update:

- `agent_governance_override` now persists rate-limit and gray-release overrides
- `agent_event_audit` now persists active/archive audit records and retention cleanup
- `agent_async_task` and `agent_async_workflow` now persist async state, request snapshots, results, and restart reconciliation
- remaining gap is no longer state durability; it is execution-runtime officialization

## 7. Practical Advice

Do not try to remove all simulated layers in one batch.

Best rollout strategy:

1. choose one vertical slice
   - recommended: `supervisor + listing-agent`
2. migrate A2A and discovery on that slice
3. prove async/status/stream behavior
4. then fan out to other domain agents

This keeps the officialization effort controlled and prevents another large parallel custom stack from growing during migration.
