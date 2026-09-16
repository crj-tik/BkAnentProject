## Purpose

This capability gives operators a consistent, dependency-aware view of service liveness, readiness, and preflight failures across local and distributed deployments.

## ADDED Requirements

### Requirement: Health endpoints SHALL distinguish liveness from readiness

A running service SHALL expose separate liveness and readiness outcomes, or an equivalent documented contract. Liveness SHALL indicate that the process can answer requests; readiness SHALL include every dependency required for the selected operating mode.

#### Scenario: Process is live but dependency is down

- **WHEN** the application process responds but a required dependency is unavailable
- **THEN** liveness SHALL remain available
- **AND** readiness SHALL be unhealthy and identify the unavailable dependency

#### Scenario: Service is ready

- **WHEN** the process is live and all required dependencies pass their configured checks
- **THEN** readiness SHALL report healthy
- **AND** the response SHALL omit passwords, tokens, and secret connection details

### Requirement: Readiness failures SHALL be actionable

Readiness and diagnostic output SHALL identify the failed dependency, the selected profile or mode, and the next configuration or connectivity action without exposing secret values.

#### Scenario: Misconfigured dependency endpoint

- **WHEN** a required dependency endpoint is missing or invalid
- **THEN** diagnostics SHALL name the missing configuration key or endpoint category
- **AND** the process SHALL return a non-success readiness result
