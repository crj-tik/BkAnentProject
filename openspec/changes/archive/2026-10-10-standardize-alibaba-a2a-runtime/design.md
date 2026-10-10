# Design

## Context

See `proposal.md` and `specs/official-a2a-runtime/spec.md` for the motivation and observable behavior. The current Supervisor already has an Alibaba official A2A client/server path, but `DelegatingA2aAgentClient`, registry discovery, and descriptor configuration still allow a custom HTTP path. The official Alibaba server adapter receives an A2A message, places request metadata into the agent run context, and returns official message/task/artifact results; it does not consume or produce the internal Supervisor task DTOs as wire envelopes.

The implementation spans `agent-service`, shared agent contracts, and distributed registration metadata. Existing graph nodes, handoff persistence, retry logic, and public Supervisor APIs depend on normalized internal request/response values, so removing the custom transport must not remove that internal seam.

## Goals / Non-Goals

**Goals:**

- Make official Alibaba A2A discovery and invocation the single network boundary.
- Keep one internal anti-corruption adapter between graph orchestration and official A2A Message/Task/Artifact values.
- Give sync, stream, and async execution the same correlation, idempotency, error, and artifact behavior.
- Fail closed for custom or incomplete registrations with actionable diagnostics.
- Make the migration verifiable with unit, contract, and configuration tests.

**Non-Goals:**

- Changing MCP transports, endpoints, tool discovery, or tool execution.
- Replacing the graph's internal `AgentTaskInvokeRequest`/`AgentTaskInvokeResponse` model in this change.
- Adding business behavior to the eight existing domain agents.
- Introducing a second A2A protocol or a compatibility HTTP endpoint.

## Decisions

### 1. Use a single official runtime adapter

`OfficialA2aAgentClient` becomes the only runtime client used by Supervisor. The delegating client and custom HTTP client/discovery branch are removed or reduced to compile-time migration shims with no reachable production path. Runtime resolution accepts only an official Agent Card and official provider metadata; an `auto` value may resolve to official only after successful official discovery and MUST NOT fall back to custom HTTP.

**Alternative considered:** Keep the delegating client and mark the custom branch deprecated. Rejected because a fallback would preserve the protocol ambiguity and allow a registration to silently bypass official A2A semantics.

### 2. Keep internal DTOs behind an anti-corruption boundary

The graph continues to create an internal invocation request and consume an internal invocation response. The official adapter maps the request instruction into an A2A text part, maps correlation and supported orchestration data into a reserved Supervisor metadata object (while preserving the A2A `threadId` field), and maps official message/task/artifact values back into the internal response. No internal DTO is used as an HTTP body.

The metadata mapping is centralized and versioned in one adapter so new internal fields do not accidentally become undocumented wire fields. Unknown remote metadata is ignored unless it is part of the documented Supervisor context; remote values always take precedence only when they are authoritative task or artifact identities.

**Alternative considered:** Pass every internal field as a flat top-level metadata key. Rejected because it risks collisions with A2A/Alibaba metadata and makes versioning of Supervisor-only fields difficult.

### 3. Derive all operations from official A2A capabilities

The adapter uses the Agent Card and official client operations for message send, streaming, task creation, task retrieval, and artifact handling. Streaming requests set the official streaming indicator in A2A metadata and use the capability advertised by the card; no custom stream path or custom response envelope is selected. Asynchronous execution stores the official task identity and polls or resumes it through official task operations.

If the card does not advertise a requested capability, the adapter returns a capability error before attempting a request. If the remote response has a failed task state or protocol error, the adapter creates a failed internal response with the remote error context.

**Alternative considered:** Continue constructing URLs from `a2aTaskCreatePath`, `a2aTaskStatusPath`, and `a2aTaskStreamPath`. Rejected because those fields describe the removed custom transport and bypass the official capability contract.

### 4. Simplify descriptor and registration configuration

Registration metadata retains the official Agent Card location and the official provider marker. Custom provider values, custom Agent Card response formats, and custom task path fields are removed from active configuration or rejected during validation. Existing official registrations remain valid; deployment validation reports every registration that still relies on removed fields.

The internal normalized `AgentCard` projection may remain for catalog and routing consumers, but it is populated only from an official Agent Card. Internal async wrapper types may remain where graph services use them; their names and fields do not define a network contract.

**Alternative considered:** Leave custom path fields in descriptors as ignored compatibility fields. Rejected because silently ignored settings make an installation appear configured while routing through a different contract.

### 5. Remove only unreachable custom support

After callers are migrated, delete the custom HTTP invocation/discovery implementations and support constants/types that have no production references. Keep shared correlation helpers and internal async result wrappers when graph, persistence, or handoff code still consumes them. Compile-time references and tests are the gate for each deletion.

**Alternative considered:** Delete all `AgentTaskInvoke*` and async wrapper types together. Rejected because graph routing, persistence, retry, and handoff currently use them as internal values.

## Risks / Trade-offs

- **[Risk] Existing external custom HTTP agents stop working.** → Validate every registered agent before deployment, publish the official Agent Card contract, and provide a migration report listing each rejected registration.
- **[Risk] Official cards may advertise no streaming capability.** → Fail with a capability error or use the documented non-streaming fallback; never silently call a custom stream endpoint.
- **[Risk] Remote A2A responses may omit Supervisor-only fields.** → Preserve originating correlation values in the internal adapter when the omission is unambiguous, and cover this with mapping tests.
- **[Risk] Artifact events can be observed more than once during stream completion and polling.** → Deduplicate by official artifact/task identity before persistence.
- **[Risk] Alibaba SDK behavior changes across versions.** → Pin the existing compatible dependency, isolate SDK calls in the adapter, and run official message/task/stream contract tests against the supported version.
- **[Trade-off] A single runtime reduces interoperability choices.** → It makes failures explicit and aligns all current domain agents with the protocol they already publish.

## Migration Plan

1. Inventory and validate all static and dynamic registrations against official Agent Cards; fail the validation build for custom-only registrations.
2. Deploy the adapter and registry validation while current official registrations continue serving traffic.
3. Update any remaining external agents to publish an official Agent Card and Alibaba A2A endpoint, then verify sync, stream, async, artifact, and error flows.
4. Remove custom client/discovery code, custom path configuration, and obsolete tests after the migration report is clean.
5. Monitor discovery failures, capability errors, task completion latency, and artifact deduplication after rollout.

Rollback is an application-version rollback only. A rollback may temporarily restore the former custom path, but the deployment must also restore the prior registration configuration; the new official-only configuration is intentionally not forward-compatible with the removed custom provider.

## Open Questions

None. The supported wire contract, internal boundary, capability behavior, and migration sequence are defined above.
