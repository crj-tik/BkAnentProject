## Purpose

This capability makes the repository understandable and repeatable to start during early development, while clearly identifying external services and configuration that are still required for the complete distributed system.

## ADDED Requirements

### Requirement: Single-service startup SHALL be scoped to the selected service

The repository SHALL provide a documented command that starts a selected Spring Boot service without attempting to run the root Maven aggregator as an application. The command SHALL work after building the selected module and its upstream modules.

#### Scenario: Start the authentication service from a clean checkout

- **WHEN** a developer follows the documented authentication-service startup sequence
- **THEN** Maven SHALL build required upstream modules separately and invoke `spring-boot:run` only for `auth-service`
- **AND** the root aggregator SHALL not fail with a missing-main-class error

### Requirement: A local authentication smoke profile SHALL run without Nacos or MySQL

The repository SHALL provide a local-only profile for `auth-service` that uses an isolated development datastore and disables remote discovery/config-center dependencies. This profile SHALL be explicitly marked as development-only and SHALL NOT alter production defaults.

#### Scenario: Start the local authentication smoke service without infrastructure

- **WHEN** Java 17 and Maven are available but Nacos and MySQL are not running
- **THEN** `auth-service` SHALL start under the local smoke profile
- **AND** `GET /auth/health` SHALL return an UP response

### Requirement: Dependency diagnostics SHALL be available before full startup

The repository SHALL provide a non-destructive diagnostic command that checks required ports and reports the corresponding environment variables or services. The diagnostic SHALL return a non-zero exit code when a required dependency for the selected run mode is unavailable.

#### Scenario: Missing Nacos is detected

- **WHEN** the external-distributed run mode is selected and the configured Nacos endpoint is unreachable
- **THEN** the diagnostic SHALL identify Nacos as unavailable and show the configured endpoint
- **AND** it SHALL not modify databases, configuration servers, or application files

### Requirement: Nacos configuration bootstrap SHALL be documented

The repository SHALL document the data IDs, group, namespace, and import sequence needed to load the checked-in Nacos templates. The documentation SHALL distinguish the local smoke profile from the full distributed profile.

#### Scenario: Import the checked-in templates

- **WHEN** an operator prepares a Nacos instance for the distributed profile
- **THEN** the operator SHALL be able to identify the expected namespace, `DEFAULT_GROUP`, and service data IDs from repository documentation
- **AND** the repository SHALL not imply that merely cloning the project automatically imports those templates
