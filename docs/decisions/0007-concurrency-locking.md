# ADR-0007: Current namespace lock and granular successor

- Status: Current strategy accepted; replacement planned but undecided
- Date: 2026-10-09

## Context

Create, rename, move, and restore make a check-then-write decision inside an owner/folder namespace. Unique indexes prevent invalid commits, but concurrent requests also need predictable state transitions and understandable errors. Restore and permanent-delete additionally compete for the same eligible Trash state.

## Decision

Keep the existing pessimistic write lock on the owner's `users` row as the correctness baseline. Both folder and file namespace mutations use this row, so operations for one owner serialize while different owners remain independent. For competing lifecycle transitions, acquire the lock and then read eligibility in the same transaction.

Plan a measured refactor to a more granular namespace lock. Do not select the final mechanism until operations, cross-namespace lock ordering, concurrency tests, and contention measurements are defined. Candidate mechanisms include locking parent-folder rows, dedicated namespace lock rows, PostgreSQL advisory locks, or optimistic writes with unique-constraint retry.

## Consequences

- The current strategy is easy to explain and aligns application decisions with database uniqueness constraints.
- A single user's unrelated file and folder mutations block each other, limiting same-user concurrency.
- Restore and permanent-delete request lock the owner row before fetching their shared eligible Trash state. Other file operations still require a read/lock/write audit during the refactor.
- Granular locks can improve concurrency but introduce multiple-key ordering and deadlock risks, especially for moves.
- Database constraints remain mandatory after any lock refactor.

## Verification required before replacement

- Deterministic tests for same-name creates and renames.
- Conflicting source/destination moves with stable lock ordering.
- PostgreSQL-backed restore versus permanent-delete mutual exclusion; unit interaction-order tests exist but do not prove transaction serialization.
- Unrelated folder operations demonstrating safe parallelism.
- A reproducible baseline and post-change contention measurement.

No performance benchmark currently exists, so this ADR makes no throughput claim.
