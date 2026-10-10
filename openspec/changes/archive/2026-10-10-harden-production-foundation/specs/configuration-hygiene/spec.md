## ADDED Requirements

### Requirement: Distributed profiles SHALL select explicit real providers

The distributed and production configuration profiles SHALL require an explicit integration mode and SHALL refuse to start or process business requests when a simulated OCR, e-signature, notification, media, or promotion provider is selected. Local development MAY select simulated providers only when the local mode is explicit.

#### Scenario: Mock provider is selected outside local mode

- **WHEN** a distributed or production profile resolves a simulated provider or omits the required real-provider configuration
- **THEN** the service SHALL fail readiness or startup with the provider category and configuration key
- **AND** it SHALL not report a simulated business operation as a real success

#### Scenario: Mock provider is selected in explicit local mode

- **WHEN** a developer starts a service with the documented local integration mode
- **THEN** simulated providers MAY be enabled
- **AND** their responses SHALL be visibly marked as simulated in logs or returned operation metadata
