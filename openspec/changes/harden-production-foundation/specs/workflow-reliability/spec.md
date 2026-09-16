## Purpose

This capability makes persisted Agent asynchronous work recoverable, bounded, idempotent, and observable across service restarts and multiple worker instances.

## ADDED Requirements

### Requirement: Persisted asynchronous work SHALL survive worker restart

Accepted and running asynchronous tasks and workflows SHALL remain recoverable after a worker process stops or restarts. A restart SHALL not convert unfinished work to a terminal failure solely because the previous process ended; stale ownership SHALL be reclaimable after a configured lease timeout.

#### Scenario: Worker restarts with accepted work

- **WHEN** a worker stops after an asynchronous task or workflow is accepted but before completion
- **THEN** a later worker SHALL discover and dispatch the persisted work
- **AND** the work SHALL not be marked failed merely because of the restart

#### Scenario: Running lease becomes stale

- **WHEN** the owner of running work stops renewing its lease beyond the configured timeout
- **THEN** another worker MAY reclaim the work atomically
- **AND** only one worker SHALL own the reclaimed execution at a time

### Requirement: Asynchronous dispatch SHALL be bounded and idempotent

The asynchronous runtime SHALL enforce a configured concurrency and queue bound. Claiming work SHALL be atomic, duplicate dispatch attempts SHALL not create duplicate executions, and terminal state transitions SHALL be applied idempotently.

#### Scenario: Concurrent workers claim the same work

- **WHEN** multiple workers dispatch the same accepted record concurrently
- **THEN** exactly one claim SHALL succeed
- **AND** the other workers SHALL skip execution for that record

#### Scenario: Dispatcher reaches its concurrency limit

- **WHEN** all configured execution slots or the bounded queue are occupied
- **THEN** new work SHALL remain persisted for a later polling cycle or be rejected with an explicit capacity error
- **AND** the process SHALL remain responsive

### Requirement: Retry and cancellation outcomes SHALL be explicit

Transient failures SHALL be retried only up to a configured attempt limit, permanent failures SHALL become terminal with a safe error classification, and cancellation SHALL prevent new execution claims. Persisted records SHALL retain enough status and error information for operators to determine the final outcome.

#### Scenario: Transient execution failure

- **WHEN** an asynchronous operation fails with a classified transient error and attempts remain
- **THEN** the record SHALL return to a dispatchable state with an incremented attempt count
- **AND** the next retry SHALL respect the configured backoff or retry delay

#### Scenario: Permanent failure or exhausted retries

- **WHEN** an operation fails permanently or reaches its attempt limit
- **THEN** the record SHALL enter a terminal failed state
- **AND** it SHALL persist a safe error classification without secrets or full provider credentials

#### Scenario: Cancellation races with dispatch

- **WHEN** cancellation is requested before a record is claimed
- **THEN** the dispatcher SHALL not claim new execution for that record
- **AND** the final status SHALL remain an explicit cancelled outcome
