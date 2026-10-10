# Tasks

## 1. Establish the official-only contract

- [x] 1.1 Inventory every static and dynamic agent registration, record its Agent Card/provider/capability metadata, and add a validation report or test that identifies any custom-only registration before runtime changes are enabled.
- [x] 1.2 Define and implement the versioned Supervisor metadata mapping for instruction, thread/session, task/parent-task, trace, source/target, intent/domain, structured context, constraints, expected output, idempotency, and stream intent; verify mapping round-trips through focused adapter tests without serializing internal DTOs as the HTTP body.
- [x] 1.3 Document the official-only registration contract, required Agent Card capabilities, rejected custom provider values, and the MCP non-goal in the change and deployment documentation; verify the documentation names the migration failure for each unsupported registration.

## 2. Make official A2A the only client path

- [x] 2.1 Consolidate synchronous invocation in the official Alibaba A2A adapter, mapping internal requests to official message-send operations and official message/task/artifact results back to internal responses; verify structured output, status, correlation, and remote error mapping with unit tests.
- [x] 2.2 Implement official streaming capability checks and metadata, consume incremental message/artifact events, emit the terminal outcome, and deduplicate repeated artifacts; verify supported, unsupported, and failed stream scenarios with contract tests.
- [x] 2.3 Implement official asynchronous task creation, retrieval, resume, and terminal-state mapping using the Agent Card and official task operations; verify idempotent retry and task correlation without custom create/status response envelopes.
- [x] 2.4 Remove the reachable custom HTTP invocation branch and custom response parsing, then verify no production code can select it and that the project compiles without the removed path.

## 3. Simplify discovery and runtime configuration

- [x] 3.1 Update static and dynamic registries to require official Agent Card discovery and to fail closed for custom providers, missing cards, invalid cards, and unsupported requested capabilities; verify each failure returns an actionable configuration or discovery error.
- [x] 3.2 Remove active use of custom Agent Card discovery, custom task path fields, custom provider enum values, and custom HTTP endpoint defaults from descriptor/configuration binding; verify official Nacos registrations still resolve with the existing `/a2a` card and endpoint metadata.
- [x] 3.3 Keep only internal normalized Agent Card and async wrapper values that still have graph, persistence, or handoff callers; remove unreachable custom support types and update compile-time references; verify with a full module compile and reference search.

## 4. Preserve Supervisor graph and handoff behavior

- [x] 4.1 Verify and adjust request construction, handoff propagation, workflow resume, and retry code so parent task, trace, session/thread, structured context, constraints, expected output, idempotency, and stream intent reach the official adapter; verify focused graph and handoff tests.
- [x] 4.2 Verify persistence and routing consume normalized official results, preserve originating correlation values when remote responses omit optional fields, and persist each artifact once; verify with response merge, route decision, and artifact persistence tests.
- [x] 4.3 Confirm the eight domain `ReactAgent` providers remain exposed through Alibaba official A2A and do not gain custom controllers or endpoints; verify their Agent Cards and endpoint metadata in configuration tests.

## 5. Validate migration and release readiness

- [x] 5.1 Add official A2A contract tests covering Agent Card discovery, message send, streaming, asynchronous task polling/resume, artifacts, protocol errors, failed tasks, and unsupported capabilities; verify the test suite passes against the pinned Alibaba A2A dependency.
- [x] 5.2 Add regression coverage proving existing MCP clients and servers retain their configured transport and endpoint behavior; verify no A2A-only change alters MCP configuration or tool execution.
- [x] 5.3 Run repository validation (`openspec validate standardize-alibaba-a2a-runtime --strict --type change`, Maven compile, and relevant tests), review the migration report, then commit and push the completed implementation with a concise Chinese message.
