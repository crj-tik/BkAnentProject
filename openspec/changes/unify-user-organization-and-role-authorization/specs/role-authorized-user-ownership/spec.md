## Purpose

Ensure restricted operations are authorized through explicit role grants and that platform user ownership is migrated from legacy business identifiers without losing or inventing historical relationships.

## ADDED Requirements

### Requirement: Restricted permissions SHALL derive only from effective roles
For a valid account the effective restricted permission set SHALL be the union of active catalog permissions explicitly bound to the user's currently effective active roles. Job roles and management roles SHALL use the same rule. A businessAdmin flag, role label, organization membership, request body or model output SHALL NOT independently grant permissions. Universal basic operations SHALL require authentication and applicable ownership and state checks without requiring a permission code.

#### Scenario: Administrator requires an explicit role grant
- **WHEN** a user has an administrator compatibility flag but no effective role granting a restricted operation
- **THEN** the operation is denied after migration to role-only authorization

#### Scenario: Effective roles contribute a union
- **WHEN** an active user has two valid roles granting distinct active permissions
- **THEN** the signed authorization includes both granted permissions and no additional permission derived from the role names

### Requirement: Administrator migration SHALL preserve effective access safely
Migration SHALL inventory current administrators and effective permissions, grant an explicit administrator role and its permissions before switching authorization, compare the effective sets, preserve at least one active authorized administrator and audit changes. The legacy businessAdmin response SHALL be derived from the effective management role after cutover; it SHALL NOT remain a separate authority. New catalog permissions SHALL require an explicit role grant.

#### Scenario: Administrator role removed
- **WHEN** an administrator role is revoked and another authorized administrator remains
- **THEN** the user's former role-derived permissions and compatibility administrator value are invalidated within the established freshness bound

#### Scenario: Last administrator removal
- **WHEN** an action would remove the final active user able to administer users and roles
- **THEN** the action is rejected atomically

### Requirement: User ownership SHALL be migrated with evidence
Business records representing platform-user ownership SHALL use trusted userId as their owner; requests and LLM output SHALL NOT establish the authenticated actor. Legacy brokerId records SHALL be mapped only with authoritative evidence and a unique mapping; unmapped or ambiguous records SHALL remain isolated for remediation. Existing business entity identifiers with independent meaning SHALL be retained. Old and new consumers SHALL remain compatible until domain-specific migration and verification are complete.

#### Scenario: Unmapped legacy owner
- **WHEN** a historical brokerId has no verified unique user mapping
- **THEN** migration does not invent an owner or expose the record as belonging to an arbitrary user

#### Scenario: Forged tool input
- **WHEN** an LLM supplies another user's id in a tool request during an authenticated run
- **THEN** downstream authorization retains the signed actor and rejects any inconsistent owner claim

