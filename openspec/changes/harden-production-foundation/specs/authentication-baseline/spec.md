## MODIFIED Requirements

### Requirement: Access tokens SHALL be signed, expiring, and revocable

The authentication service SHALL validate token integrity and expiry, SHALL reject tokens that have been explicitly revoked by logout, and SHALL reject tokens whose account no longer exists, is inactive, or is logically deleted. Token signing material SHALL be supplied by deployment configuration or generated only for an isolated local process.

#### Scenario: Tampered or expired token is rejected

- **WHEN** a token signature is changed or its expiry has passed
- **THEN** token validation SHALL return false

#### Scenario: Token for an invalid account is rejected

- **WHEN** a token belongs to an unknown, inactive, or deleted account
- **THEN** token validation SHALL return false

#### Scenario: Logout revokes a token

- **WHEN** a client logs out with a valid access token
- **THEN** subsequent validation of that token SHALL return false

## ADDED Requirements

### Requirement: Gateway SHALL propagate authenticated account context

After successful access-token validation, the gateway SHALL propagate the authenticated account identifier and a stable principal marker to downstream services through trusted internal request context. It SHALL remove any client-supplied values for those context fields before forwarding a request, and it SHALL not propagate identity context when authentication fails.

#### Scenario: Authenticated request carries trusted identity

- **WHEN** a request contains a valid access token for an active account
- **THEN** the gateway SHALL forward the request with the account identifier resolved by the authentication service
- **AND** a downstream service SHALL be able to distinguish the gateway-injected context from an unauthenticated request

#### Scenario: Client cannot forge gateway identity

- **WHEN** a client sends identity headers without a valid access token, or sends conflicting identity headers with a valid token
- **THEN** the gateway SHALL discard those client values
- **AND** it SHALL return an authentication failure or forward only the identity resolved from the token
