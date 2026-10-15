## Purpose

Provide controlled business action receipts and immutable previews that connect the work canvas to existing domain services and approvals, preserving precise scope and honest provider execution outcomes.

## ADDED Requirements

### Requirement: Domain actions SHALL be authorized and idempotent
The API SHALL validate principal, target scope, context/result versions and action availability, with recoverable receipts for repeated submissions and explicit unknown outcomes.

#### Scenario: Client supplies a different action payload under one request ID
- **WHEN** that conflicting request is submitted
- **THEN** the API rejects it without performing another business action

### Requirement: Approval SHALL bind the actual business snapshot
Controlled side effects SHALL bind immutable plan/content/material versions in actual hashed invocation arguments. Approval callback version SHALL come from pendingApproval.subjectVersion rather than artifact or context versions.

#### Scenario: Content changes after a publish plan is prepared
- **WHEN** the old plan is confirmed for execution
- **THEN** it is rejected as stale instead of publishing dynamically resolved latest content

### Requirement: Provider and evidence limits SHALL remain visible
The API SHALL distinguish simulated publishing/effects, internal payout bookkeeping, status-only contract checks and protected media/files from real external execution or clause evidence.

#### Scenario: No publishing provider is configured
- **WHEN** a user prepares or executes publication
- **THEN** a plan can be stored but no real publication success is claimed
