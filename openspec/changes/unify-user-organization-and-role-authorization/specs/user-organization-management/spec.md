## Purpose

Provide a consistent platform user profile and auditable company, store and department membership maintained by Auth for users and downstream business services.

## ADDED Requirements

### Requirement: Auth SHALL manage common user profiles
Auth SHALL maintain a stable userId, account status, login credentials and a minimal common profile. An authenticated user SHALL be able to read and update permitted profile fields without supplying their own identity; role, organization membership, account status and credentials SHALL NOT be writable through the profile update.

#### Scenario: Profile modification cannot alter privileges
- **WHEN** a user updates their own display name and submits a role or another userId
- **THEN** the service rejects unauthorized fields and makes no privilege or other user's profile change

### Requirement: Auth SHALL maintain organization structure and membership
Auth SHALL maintain company, store and department nodes and their explicit parent and member relationships. Each department and store SHALL belong to a company, hierarchy cycles and membership in invalid nodes SHALL be rejected, and membership changes SHALL be auditable and effective only within their validity interval. The initial policy SHALL allow one current primary organization per user, with an explicit extensible membership model; a store SHALL map to its organization node without inferring equality of unrelated identifiers.

#### Scenario: Company has a store and operations department
- **WHEN** an administrator creates a company, a store and an operations department underneath it and assigns one user to each child
- **THEN** querying authorized organization and member views returns the correct common company and distinct memberships

#### Scenario: Removed membership stops granting store scope
- **WHEN** a user's effective store membership ends
- **THEN** future store-scoped actions reject the old membership after authorization invalidation or the bounded freshness window

### Requirement: Development acceptance SHALL use separate sample users
The development environment SHALL support an idempotent, explicitly invoked setup of one company, one store, one operations department, one non-administrator broker user and one non-administrator operations user. Credentials SHALL be generated or supplied outside source control, never printed or seeded as fixed passwords, and setup SHALL NOT overwrite existing unrelated users or grants.

#### Scenario: Independent user acceptance
- **WHEN** both sample users authenticate through the actual API
- **THEN** each receives their own userId, assigned role and department, and neither can impersonate the other or access an unauthorized private resource

