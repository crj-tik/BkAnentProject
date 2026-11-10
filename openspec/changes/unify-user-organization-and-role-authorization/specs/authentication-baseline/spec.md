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
