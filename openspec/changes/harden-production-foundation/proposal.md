## Why

The project now has a runnable authentication smoke path, but the distributed architecture still has several production-risk gaps: the gateway validates a token without propagating a trusted identity, asynchronous Agent work is tied to process-local executors, and prototype providers can report success without performing a real external operation. These issues should be addressed before adding more business capabilities or exposing the platform to real users.

## What Changes

- **BREAKING** Extend the shared authentication RPC contract so the gateway can resolve a valid token to an active account principal and pass trusted identity context downstream.
- Require token validation to re-check account status and logical deletion, and make gateway authorization failures explicit and observable.
- Replace process-local asynchronous Agent task/workflow ownership with bounded, recoverable dispatch semantics: persisted states, startup recovery, idempotency, retry limits, and failure classification.
- Add an explicit integration mode that prevents mock OCR, e-signature, notification, media, and promotion providers from being selected by distributed/production profiles.
- Add dependency readiness checks and failure-fast startup diagnostics for service-owned external dependencies.
- Add CI quality gates for compile, tests, packaging, OpenSpec validation, and credential-shaped configuration scans.

## Capabilities

### New Capabilities

- `workflow-reliability`: Recoverable, bounded, idempotent asynchronous Agent task and workflow execution.
- `provider-safety`: Explicit provider modes and fail-closed behavior for simulated external integrations.
- `service-readiness`: Dependency readiness reporting and actionable service startup state.
- `ci-quality-gates`: Repeatable repository validation in continuous integration.

### Modified Capabilities

- `authentication-baseline`: Token validation must resolve an active account principal and support trusted gateway identity propagation.
- `runtime-bootstrap`: Distributed startup must expose dependency readiness and fail clearly when required dependencies are unavailable.
- `configuration-hygiene`: Distributed profiles must explicitly select real providers and may not silently fall back to simulations.

## Impact

- `common` RPC interfaces and authentication DTOs; `gateway` filters; `auth-service` token/account lookup.
- `agent-service` asynchronous task/workflow services, persistence transitions, and retry/recovery behavior.
- Contract, notification, media, and promotion provider selection/configuration.
- Service health/readiness endpoints, CI workflow files, configuration checks, and OpenSpec specifications.
- Existing gateway-to-auth consumers must adopt the expanded RPC contract and downstream identity headers.
