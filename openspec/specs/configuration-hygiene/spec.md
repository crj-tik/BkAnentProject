## Purpose

This capability keeps credentials and distributed endpoint addresses safe for a public early-stage repository while preserving convenient environment-specific configuration for local, staging, and production deployments.

## Requirements

### Requirement: Repository configuration SHALL not contain usable provider credentials

Checked-in configuration SHALL contain environment-variable references or clearly non-secret placeholders only. Provider API keys and production credentials SHALL not have usable fallback values in Nacos templates or application resources.

#### Scenario: Scan checked-in configuration

- **WHEN** repository configuration is scanned for provider key patterns
- **THEN** the scan SHALL find no usable DeepSeek, DashScope, database, object-storage, or message-broker credentials
- **AND** an example environment file SHALL identify the variables required for local or distributed operation

### Requirement: Advertised distributed service addresses SHALL be configurable

A2A agent cards and other cross-service endpoint advertisements SHALL use an explicitly configurable public base URL or service-discovery address. Loopback addresses SHALL be reserved for an explicitly selected local mode.

#### Scenario: Deploy services on separate hosts

- **WHEN** a service is deployed outside the supervisor process host
- **THEN** its advertised A2A endpoint SHALL resolve to the configured reachable address
- **AND** it SHALL not default to another host's `127.0.0.1`

### Requirement: Production and local defaults SHALL be distinguishable

Configuration documentation SHALL identify development-only defaults and SHALL require operators to provide deployment secrets and externally reachable endpoint values for distributed operation.

#### Scenario: Prepare a distributed deployment

- **WHEN** an operator reads the configuration guide
- **THEN** the operator SHALL see which environment variables must be supplied
- **AND** the guide SHALL warn that local demo credentials and simulated providers are not production integrations
