## Purpose

This capability establishes the minimum trustworthy authentication behavior required before the gateway and Agent endpoints are exposed beyond a local prototype environment.

## Requirements

### Requirement: Login SHALL verify credentials and account status

The authentication endpoint SHALL reject blank credentials, unknown users, inactive or deleted accounts, and passwords that do not match the stored password hash. Successful login SHALL return access and refresh tokens without exposing the stored password hash.

#### Scenario: Wrong password is rejected

- **WHEN** a known active user submits an incorrect password
- **THEN** the endpoint SHALL return an authentication failure
- **AND** it SHALL not issue an access token

#### Scenario: Valid credentials are accepted

- **WHEN** a known active user submits the correct password
- **THEN** the endpoint SHALL return signed access and refresh tokens with the account identity

### Requirement: Access tokens SHALL be signed, expiring, and revocable

The authentication service SHALL validate token integrity and expiry, and SHALL reject tokens that have been explicitly revoked by logout. Token signing material SHALL be supplied by deployment configuration or generated only for an isolated local process.

#### Scenario: Tampered or expired token is rejected

- **WHEN** a token signature is changed or its expiry has passed
- **THEN** token validation SHALL return false

#### Scenario: Logout revokes a token

- **WHEN** a client logs out with a valid access token
- **THEN** subsequent validation of that token SHALL return false

### Requirement: RPC authorization SHALL verify the account exists and is active

The authorization RPC SHALL not grant permission solely because user ID and permission code are non-empty. It SHALL verify that the referenced account exists, is active, and is not logically deleted before applying the configured permission baseline.

#### Scenario: Unknown user requests permission

- **WHEN** authorization is checked for an unknown, inactive, or deleted user
- **THEN** the RPC SHALL return false
