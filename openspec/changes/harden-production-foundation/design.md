## Context

The repository already has a database-backed polling dispatcher for Agent asynchronous tasks and workflows, but both services use process-local cached thread pools and mark unfinished records as failed during startup. The gateway validates only a boolean token result, so downstream services do not receive a trusted principal. Several provider implementations are intentionally simulated or return success placeholders, while distributed startup has no consistent dependency-aware readiness contract. See `proposal.md` for the motivation and the delta specifications for observable behavior.

## Goals / Non-Goals

**Goals:**

- Establish one shared authenticated-principal contract between the gateway and authentication service.
- Make the existing persisted asynchronous runtime recoverable, bounded, idempotent, and safe under restart and concurrent polling.
- Make provider simulation explicit and impossible to mistake for a production integration.
- Provide dependency-aware readiness and a clean-runner CI baseline.

**Non-Goals:**

- Replacing the existing service-discovery platform or introducing a new message broker in this change.
- Implementing every business provider integration or completing provider credentials.
- Redesigning the full RBAC model, API versioning, or cross-service transport security.
- Promising exactly-once effects from external providers; provider calls remain at-least-once and must use persisted idempotency where supported.

## Decisions

### 1. Return a principal from authentication RPC

Extend the shared RPC contract with a small nullable principal DTO rather than making the gateway parse token claims or query the user database directly. The authentication service remains the authority for token integrity, expiry, revocation, and account status. The gateway removes client-supplied identity headers, injects the resolved account ID and a fixed internal marker, and downstream code consumes only those values.

The existing boolean validation method remains temporarily for source compatibility, but the gateway will use the principal-returning method. A full authorization-policy engine is deferred; this change establishes authentication context, not endpoint-specific permission mapping.

### 2. Keep database polling, add leases and bounded execution

Retain the current persisted tables and scheduled dispatcher to avoid introducing a broker migration while the project is still in early development. Add attempt, lease, and retry timing fields, atomically claim records with a lease owner and expiry, and reclaim only stale work. Replace static cached pools with a Spring-managed bounded executor configured per service. Terminal transitions will use compare-and-set updates so duplicate pollers cannot repeat them.

Transient versus permanent errors will be classified at the dispatcher boundary. A transient failure returns the record to `ACCEPTED` after a bounded delay; permanent failures and exhausted attempts become `FAILED`. Cancellation is checked before claim and before the external operation. This provides recoverability without claiming exactly-once external side effects.

### 3. Guard provider mode at configuration and operation boundaries

Each provider-owning service will resolve an explicit `provider.mode` or equivalent integration-mode property. Mock implementations will be conditional on local mode and will add a simulation marker to their observable result/log path. Distributed and production profiles will fail readiness when a mock or missing real provider is selected. Real-provider exceptions remain failures and never fall back to mocks.

This is preferred over deleting mock classes because local smoke tests and UI development still need deterministic substitutes. Provider-specific credentials remain environment/Nacos inputs and are not added to the repository.

### 4. Use a small common readiness contract

Use Spring Boot Actuator liveness/readiness groups plus service-local dependency indicators or preflight checks. Required dependencies are declared by the selected profile; local auth remains independently runnable. Existing diagnostics remain non-destructive and become the operator-facing preflight path. Readiness output contains categories and safe reasons only.

### 5. Make CI infrastructure-independent by default

Add a GitHub Actions workflow that uses Java 17 and checked-in Maven configuration, runs compile/test/package, strict OpenSpec validation, and a conservative credential scan. Tests requiring Nacos, MySQL, or provider accounts stay outside the baseline profile until an explicit integration environment exists. This keeps the first gate deterministic and prevents CI from depending on private developer settings.

## Risks / Trade-offs

- [Risk] Lease reclaim can cause an external operation to be attempted twice after a worker pauses. → [Mitigation] Persist idempotency keys, use compare-and-set terminal updates, and document at-least-once semantics.
- [Risk] Rejecting mocks in distributed mode may make a service fail to start for developers using incomplete configuration. → [Mitigation] Keep a documented local mode and report the exact mode/provider configuration key in readiness output.
- [Risk] Trusting gateway-injected headers is unsafe if services are directly reachable. → [Mitigation] Mark the header contract as internal, strip incoming values at the gateway, document network isolation/TLS as deployment requirements, and avoid treating the marker as a replacement for transport authentication.
- [Risk] A broad readiness check can delay rollout when an optional dependency is down. → [Mitigation] Classify dependencies as required per profile and include only required categories in readiness.

## Migration Plan

1. Deploy the shared RPC DTO and compatible provider before switching the gateway to principal propagation.
2. Apply additive async table migrations, deploy bounded dispatchers, and allow old accepted records to be recovered by the new lease logic.
3. Set explicit local/distributed provider modes and verify readiness before enabling distributed traffic.
4. Enable CI gates on pull requests. Rollback uses the previous application binaries and leaves additive columns unused; provider mode and readiness configuration can be reverted independently.
