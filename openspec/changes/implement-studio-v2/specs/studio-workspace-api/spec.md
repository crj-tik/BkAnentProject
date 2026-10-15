## Purpose

Expose durable, owner-bound workspaces that aggregate existing supervisor execution and business artifacts, allowing authorized users to restore ongoing work without creating a second orchestration engine.

## ADDED Requirements

### Requirement: Work SHALL be durable and owner bound
The API SHALL persist work context, run/message/artifact associations and whitelisted UI preferences, and enforce current principal ownership on all reads and writes.

#### Scenario: Another user knows a work ID
- **WHEN** that user requests the work or a linked private result
- **THEN** access is denied without revealing its content or counts

### Requirement: Work commands SHALL retain request identity
Work creation and run submission SHALL return the existing receipt for identical retries and reject reuse with different content. NEW and CONTINUE SHALL preserve existing runner policy boundaries.

#### Scenario: Submission response is lost
- **WHEN** the same owner repeats an identical request ID and payload
- **THEN** the API returns its prior accepted submission instead of creating another run

### Requirement: Recovery SHALL distinguish context and presentation versions
Context changes SHALL use expectedContextRevision, preserve prior snapshots and indicate affected results; UI-state SHALL use an independent revision. History SHALL be paginated within authorized scope.

#### Scenario: Context update races with another tab
- **WHEN** a stale expectedContextRevision is submitted
- **THEN** the API returns a conflict and the current revision without silently overwriting conditions
