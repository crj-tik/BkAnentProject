## ADDED Requirements

### Requirement: Refresh SHALL be reachable without a valid access token
The Gateway SHALL permit the exact POST /auth/refresh operation to reach refresh-token verification without requiring a valid access token, while preserving authentication of unrelated paths and existing account, role and revocation checks.

#### Scenario: Access expired but refresh remains valid
- **WHEN** a client submits a valid refresh token to POST /auth/refresh
- **THEN** authentication can issue the replacement token pair through the Gateway

#### Scenario: Similar path is not public
- **WHEN** an unauthenticated client accesses another method or a path with a refresh-like suffix
- **THEN** the refresh exemption does not authorize that request
