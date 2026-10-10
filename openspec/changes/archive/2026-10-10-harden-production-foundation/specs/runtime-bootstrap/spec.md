## ADDED Requirements

### Requirement: Distributed services SHALL expose dependency readiness

Each service participating in the distributed run mode SHALL expose a readiness signal that distinguishes process liveness from the availability of required external dependencies. The readiness signal SHALL identify unavailable dependencies and SHALL not report the service as ready while a required dependency is unavailable.

#### Scenario: Required dependency is unavailable

- **WHEN** a distributed service process is running but a required Nacos, database, broker, or object-storage dependency cannot be reached
- **THEN** the service SHALL remain live enough to expose diagnostics
- **AND** its readiness signal SHALL report failure with the dependency name and a safe actionable reason

#### Scenario: Required dependencies are available

- **WHEN** all dependencies required by the selected service profile are reachable
- **THEN** the readiness signal SHALL report ready
- **AND** the signal SHALL not expose credentials or connection secrets
