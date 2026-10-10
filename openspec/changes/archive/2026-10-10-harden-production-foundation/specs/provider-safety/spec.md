## Purpose

This capability prevents prototype integrations from silently appearing as successful production operations and gives each external-provider category a safe, explicit operating mode.

## ADDED Requirements

### Requirement: Simulated providers SHALL be isolated and transparent

Simulated provider implementations SHALL be selectable only through an explicit local-development mode. Every simulated result SHALL be identifiable as simulated to the caller and in operational logs, and SHALL not be presented as delivery, signing, publishing, or generation performed by a real external provider.

#### Scenario: Local simulation is invoked

- **WHEN** a local developer invokes an enabled simulated provider
- **THEN** the operation result SHALL include a simulation marker or equivalent metadata
- **AND** the log entry SHALL identify the provider as simulated

#### Scenario: Production attempts to use simulation

- **WHEN** a production or distributed service resolves a simulated provider
- **THEN** provider initialization or readiness SHALL fail
- **AND** no simulated success response SHALL be returned

### Requirement: Real provider failures SHALL remain observable

When a configured real provider is unavailable or rejects an operation, the service SHALL return a failure or pending state consistent with the operation contract, persist an actionable safe error, and SHALL not fall back silently to a simulated provider.

#### Scenario: External provider is unavailable

- **WHEN** a real OCR, e-signature, notification, media, or promotion provider cannot be reached
- **THEN** the operation SHALL not be reported as completed successfully
- **AND** the failure reason SHALL be available for retry or operator diagnosis
