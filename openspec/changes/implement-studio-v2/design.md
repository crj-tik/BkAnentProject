## Context

See proposal.md and the sibling frontend docs/frontend-backend-integration-v2.md. Existing supervisor APIs already provide async execution, query, artifacts, persistent SSE, approvals and reconciliation. Current continuation freezes context/skill/allowMcp, and task artifact versions are not editable logical series.

## Goals / Non-Goals

Goals: add a thin durable Studio application layer and implement necessary domain contracts in stages P0–P4.

Non-Goals: replacing supervisor execution, weakening LR-40/41 identity, exposing internal storage, real external sends/payment/deployment, or declaring unavailable providers operational.

## Decisions

- Put /agent/studio/v1 under the current gateway route. Use current principal and owner-only work access; domain read scopes retain LR-40 rather than inventing organization grants.
- Keep work/context/UI/run/command association metadata in the agent database. Existing Memory artifacts are immutable generation inputs and lack transactional edit/OCC contracts. Studio owns its append-only draft revisions in the same database transaction as command receipts, preserving sourceArtifactId provenance without writing back or dual-writing Memory. Domain facts remain immutable source snapshots; saved marketing assets are separate explicit records. This avoids a distributed edit transaction and a second mutable authority for the same draft.
- Make contextRevision, overall work revision and uiRevision separate. Persist request hashes/receipts before dispatch and reconcile original task identity on retry.
- NEW calls existing async execution; CONTINUE only WAITING_USER_INPUT with original context and policy. Completed follow-ups are new runs in the same work.
- Project actual source results into versioned public types; raw artifacts remain unchanged. series/revision routes use stable series IDs and append-only revisions/OCC.
- Bring the read-only compare command plus registration forward into P2. P3 extends the same command system to immutable business plans and existing approval execution.
- The existing compare RPC uses a deliberately disabled unsigned listing read. Studio read-only comparison therefore uses the current signed Listing READ and shared common pure calculations/fingerprints; it does not re-enable that RPC or cached sharing. Migrating the old compare route remains a separate outstanding domain contract.
- Reuse existing approval batches: plan/version/body/material hashes must enter actual hashed arguments, callback uses pendingApproval.subjectVersion. Direct domain paths cannot bypass equivalent checks.
- Implement contracts honestly: status-only contract checks remain distinct, no-provider publication returns unavailable, settlement selections are explicit, protected files and interview tickets remain authorized.

## Risks / Trade-offs

- Async submission may be accepted before response/link persistence → recover using persisted command identity and original run, never new external submission.
- Raw artifact formats differ → explicit type mapping and safe fallback, UNKNOWN freshness where dependencies cannot be proven.
- KI-110/111 have been fixed by other work; current environment state still requires separate verification, including KI-112 startup ordering. No migration or runtime verification has been performed by this change.
- Full domain side effects, employee/work-number identity mappings, interview credential recovery and protected file APIs remain outstanding under KI-114. Implemented plan snapshots stay INCOMPLETE/NOT_CONFIGURED with no execution action; the frontend handoff identifies these missing contracts explicitly.

## Migration Plan

Add schema migrations using the repository's current manifest/checksum process without rewriting applied migrations. Ship P0 then P1/P2, then P3/P4. Preserve old endpoints and historical read semantics; add API schema/examples and per-stage verification evidence. Coordinate frontend capability availability with the separately tracked sibling change.
