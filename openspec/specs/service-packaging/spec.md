## Purpose

This capability ensures each deployable service can be packaged as a self-contained Spring Boot artifact suitable for local verification and standard process or container deployment.

## Requirements

### Requirement: Deployable service modules SHALL produce executable Spring Boot JARs

Each deployable Spring Boot module SHALL produce a JAR containing its runtime dependencies and a valid Spring Boot launcher manifest when the repository package command completes successfully.

#### Scenario: Package auth-service

- **WHEN** a developer runs the documented package command for `auth-service`
- **THEN** the generated JAR SHALL contain a Spring Boot launcher manifest with the application start class
- **AND** `java -jar` SHALL launch the service until normal shutdown or an actionable runtime dependency error

### Requirement: The root aggregator SHALL remain non-deployable

The root Maven project SHALL continue to package only as an aggregator POM and SHALL not be treated as a Spring Boot application during package or run operations.

#### Scenario: Package the full reactor

- **WHEN** the full Maven reactor is packaged
- **THEN** the root project SHALL succeed as a POM
- **AND** only deployable service modules SHALL receive executable application artifacts
