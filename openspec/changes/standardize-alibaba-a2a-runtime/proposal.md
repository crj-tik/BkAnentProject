# Proposal

## Why

Supervisor currently supports two incompatible child-agent paths: the Alibaba Cloud official A2A runtime and a custom HTTP protocol that exchanges internal `AgentTaskInvokeRequest`/`AgentTaskInvokeResponse` JSON. The current domain agents already publish official Alibaba A2A Agent Cards, while the custom path keeps registry fallbacks and wire semantics that do not match A2A Message, Task, and Artifact objects. This makes discovery, streaming, asynchronous tasks, errors, and interoperability depend on which runtime a descriptor happens to select.

The Supervisor should have one network contract. Internal graph orchestration still needs a normalized request/response model, but that model must remain an internal adapter rather than an A2A wire format.

## What Changes

- Make Alibaba Cloud official A2A the only supported child-agent runtime for Supervisor discovery and invocation.
- Remove the custom HTTP invocation, Agent Card discovery, runtime selection, and task create/status wire branches.
- Require official Agent Card discovery and official A2A Message/Task/Artifact mapping for synchronous, streaming, and asynchronous execution.
- Retain `AgentTaskInvokeRequest` and `AgentTaskInvokeResponse` as internal Supervisor normalization models used by graph, handoff, persistence, routing, and retry code; they must no longer be serialized as the network request or response body.
- Preserve correlation, idempotency, session/thread context, structured context, artifacts, and handoff behavior through official A2A metadata and task state.
- Remove obsolete custom-runtime configuration, tests, and documentation, and update runtime validation and observability to report unsupported custom registrations clearly.
- Leave MCP protocol configuration and transport behavior unchanged; this change is limited to A2A.

This is a breaking integration change for external agents that only expose the former custom HTTP endpoints. They must publish an Alibaba-compatible Agent Card and implement the official A2A endpoint before they can be registered.

## Capabilities

### New Capabilities

- `official-a2a-runtime`: Supervisor discovers and invokes child agents exclusively through Alibaba Cloud official A2A, with internal request/response normalization and consistent sync, stream, and async task handling.

### Modified Capabilities

None.

## Impact

- `agent-service`: clients, registry/discovery, descriptor/config validation, async execution, stream handling, error mapping, and tests.
- `common`: only the internal contract documentation and any obsolete custom-runtime support types that have no remaining callers.
- Nacos registrations and deployment documentation: official Agent Card and A2A endpoint metadata become mandatory; custom provider values are rejected.
- Domain services: keep their existing official `ReactAgent`/Alibaba A2A server exposure; no new custom HTTP endpoint is introduced.
- Supervisor public APIs and graph state retain their normalized internal behavior, while external child-agent interoperability becomes Alibaba A2A-only.
- MCP integrations are out of scope and remain unchanged.
