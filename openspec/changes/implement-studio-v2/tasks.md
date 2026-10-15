## 1. P0 Public contracts and authentication

- [x] 1.1 Fix exact-method refresh Gateway routing while preserving existing token verification.
- [x] 1.2 Implement principal-filtered public capability contracts and stable Studio errors.
- [ ] 1.3 Apply identity/action rules to newly exposed business endpoints and document existing scope boundaries.

## 2. P1 Persistent work

- [x] 2.1 Add work, context/UI revision, run/message/command association migrations and current initialization manifest entries.
- [x] 2.2 Implement private work creation, authorized cursor history, snapshot and whitelisted UI state.
- [x] 2.3 Implement context OCC and preserved input snapshots with affected-result freshness.
- [x] 2.4 Implement idempotent run submission/receipts using existing NEW/CONTINUE rules and runtime query.
- [x] 2.5 Implement run/message/artifact pagination and event recovery watermarks or explicit buffered recovery contract.

## 3. P2 Artifacts and comparison

- [x] 3.1 Add typed public artifact projections and stable series/revision mapping without rewriting legacy records.
- [x] 3.2 Implement authorized append-only save/history/restore with expectedVersion and source/dependency metadata.
- [x] 3.3 Implement read-only compare command, reliable receipt and server-side Work result registration.
- [x] 3.4 Correct compare snapshot/cache invalidation and price units based on authoritative source semantics.
- [x] 3.5 Implement safe AI revision references and dirty-edit conflict semantics.

## 4. P3 Controlled business actions

- [ ] 4.1 Extend command receipts and actual hashed invocation arguments with immutable plan/version bindings.
- [ ] 4.2 Implement publish prepare/read/confirm with exact content/material/account versions and unavailable-provider handling.
- [ ] 4.3 Implement selected-settlement preview/confirm and distinguish batch creation from payment bookkeeping.
- [ ] 4.4 Enforce equivalent action/principal/approval checks on retained direct business write paths.

## 5. P4 Domain integration

- [x] 5.1 Expose contract status-check separately from actual clause review/evidence and missing-document outcomes.
- [ ] 5.2 Implement authorized interview entry recovery and reliable turn-receipt contract without exposing persistent tickets.
- [ ] 5.3 Provide protected file registration/upload/read and media-task projections with actual access boundaries.
- [ ] 5.4 Provide needed professional pagination/metadata and full capability coverage with actual provider modes.

## 6. Delivery

- [x] 6.1 Compile affected modules and resolve implementation diagnostics.
- [x] 6.2 Update API/schema examples, LR/KI and deployment/rollback prerequisites with actual verification limits.
- [x] 6.3 Commit and push verified scoped changes according to repository instructions.

## Implementation handoff

P0/P1/P2 source and the frontend are delivered in this batch; full P3/P4 remains explicit work under KI-114. Publish preparation currently stores an INCOMPLETE/NOT_CONFIGURED snapshot only, and persisted interview-response lookup is a partial KI-12 improvement. No deployment or schema application has been performed. Current-work keyword/cursor history is implemented; status/needsDecision filtering remains part of the outstanding metadata work (5.4). See docs/studio-v2-api.md and the sibling frontend backend-integration-prompts-v2.md for concrete continuation instructions.

Source delivery commit 35afd8d was pushed to origin/dev-fix with explicit user authorization after automatic approval initially rejected the unverified destination. Frontend production build and affected backend module compile succeeded; no test suite or runtime migration was executed. Unrelated working-tree changes remain outside this delivery.
