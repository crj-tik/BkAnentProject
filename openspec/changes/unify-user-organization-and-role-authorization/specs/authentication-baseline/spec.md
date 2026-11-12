## MODIFIED Requirements

### Requirement: RPC authorization SHALL verify the account exists and is active

The authorization RPC SHALL reject unknown, inactive or logically deleted accounts and SHALL establish authorization only from authenticated credentials or valid restricted delegation. Effective restricted permissions SHALL derive from currently effective roles and their explicitly granted active permissions; administrator status SHALL NOT bypass this chain. Universal basic operations SHALL require valid identity without an individual permission code. Accepted signed execution contexts SHALL retain their actor, original scope and hard deadline, be locally reusable across Graph and subAgent calls, and honor version invalidation and the established bounded freshness window.

#### Scenario: Unknown user requests permission
- **WHEN** authorization is checked for an unknown, inactive, or deleted user
- **THEN** the RPC SHALL return false

#### Scenario: User ID alone is insufficient
- **WHEN** a request contains only a userId and permission code without authenticated credentials or valid delegation
- **THEN** the RPC SHALL deny authorization

#### Scenario: Repeated subAgent calls use the bound identity
- **WHEN** an authenticated graph invokes multiple subAgents under a valid bound context
- **THEN** each transport boundary verifies the signed request locally and SHALL NOT replace the actor from generated content or require an Auth RPC for every node

### Requirement: Auth management endpoints SHALL require administrator identity and exact capability
Versioned Auth account, organization, role, role-grant and business-identity management endpoints SHALL require both an Auth-derived administrator identity and the exact registered management permission for the endpoint. A management permission by itself SHALL NOT permit access or mutation; after role-only cutover the administrator identity SHALL derive only from an effective explicit `PLATFORM_ADMIN` role grant. Client-provided administrator claims SHALL never be trusted.

#### Scenario: Ordinary role receives a management permission accidentally
- **WHEN** a non-administrator's effective role contains `auth.users.manage` or `auth.roles.manage`
- **THEN** Auth management endpoints deny the request and do not disclose management data or mutate authorization state
