# ADR-0007: Transaction-scoped hierarchy coordination

- Status: Accepted; folder operations and current file metadata mutation paths implemented
- Date: 2026-10-09

## Context

Create, rename, move, and restore make a check-then-write decision inside an owner/folder namespace. Unique indexes prevent invalid commits, but concurrent requests also need predictable state transitions and understandable errors. Restore and permanent-delete additionally compete for the same eligible Trash state.

## Decision

Introduce an owner-scoped hierarchy coordinator backed by PostgreSQL transaction advisory locks. It exposes shared and exclusive modes, requires an active transaction, and executes on the connection enlisted in the current JPA transaction. PostgreSQL therefore releases the lock on commit or rollback.

The signed `bigint` key has a stable derivation: fixed namespace `0x5641554C54445256` (ASCII `VAULTDRV`) XOR the UUID most-significant 64 bits XOR the UUID least-significant 64 bits rotated left by one. Reducing 128 bits to 64 can collide; a collision only causes conservative extra serialization. The formula must not change without a coordinated deployment because mixed versions must contend on the same key.

Destination namespace locks use PostgreSQL's separate two-`integer` advisory-lock key space: fixed class `0x56444E53` plus a stable 32-bit mix of owner and destination UUIDs, with `null` representing root. Hash collisions only add conservative serialization.

Folder create, rename, move, trash, restore, and permanent-delete request acquire the exclusive hierarchy lock before any protected read. A folder purge request additionally rejects pending-purge ancestors and descendants, so overlapping parent/child intents serialize to one winner. These paths remain PostgreSQL-only operations, so the advisory lock is never held across Garage I/O.

Folder restore and permanent-delete request retain the order: exclusive owner hierarchy lock, then fresh owned Trash-row pessimistic lock. Both reject the row's own pending purge before mutation. For concurrent requests on the same folder, the first committed transition wins and the other returns `404 Not Found`; restore retains ancestor-purge rejection before root fallback. PostgreSQL tests cover both winner orders and direct pending-purge service rejection. V11 remains the final constraint guard; no locking or purge execution architecture changes are introduced by Phase 2D.2 Slice 1.

File rename, move, trash, upload reservation, and upload finalization acquire the shared hierarchy lock before loading current file or ancestor state. Each performs fresh validation and writes inside a short transaction. Garage transfer and cleanup remain outside database transactions and hierarchy locks. Because shared locks allow file mutations for the same owner to overlap, the file row has an optimistic version; upload finalization additionally uses an expected-status/version conditional transition so only one `UPLOADING -> READY|FAILED` change can win.

File restore and permanent-delete request acquire the shared hierarchy lock before the fresh eligible Trash-row read. Both pessimistically lock that row, so restore and purge request cannot both transition it. Restore validates the original ancestry while holding the hierarchy lock, falls back to root when inaccessible, and then takes a destination-scoped exclusive advisory lock before extension-preserving collision naming. Lock order is owner hierarchy, file row, then destination namespace. Future purge/reconciliation workers remain outside this coordination domain.

## Consequences

- All folder mutations for one owner still serialize; different owners normally proceed independently.
- Shared mode coordinates file rename, move, and trash with folder structural operations while allowing unrelated file metadata mutations to overlap.
- A rare derived-key collision can serialize different owners without weakening correctness.
- Restore collision allocation is serialized only within one destination; unrelated destinations can proceed concurrently.
- Upload reservation and finalization now coordinate with folder structural operations without holding the advisory lock during Garage I/O.
- Database constraints remain mandatory after any lock refactor.

## Verification and remaining migration

- PostgreSQL integration tests cover hierarchy and destination namespace compatibility/blocking, rollback release, and the transaction requirement.
- The existing concurrent folder-restore integration test exercises the migrated folder path.
- PostgreSQL-backed races cover file move versus folder trash, rename versus folder trash, same-file optimistic conflicts, upload reservation versus folder trash, trash during the transfer gap, competing finalization transitions, both file restore/permanent-delete winner orders, restore versus folder trash/move, same-name restores, folder purge versus child/file restore, folder move, upload reservation, and both parent/child purge winner orders.
- Add future purge/reconciliation race coverage when those workers are implemented.
- A reproducible baseline and post-change contention measurement.

## Phase 2D.1 storage admission and file purge

New upload allocation insertion follows the file flush under the existing shared
owner hierarchy lock. Storage write admission reads immutable allocation identity
to identify the owner, then takes the shared owner hierarchy lock, freshly queries
and pessimistically locks the eligible UPLOADING file row, and consumes the
allocation row. The order is hierarchy, file, allocation; commit precedes PUT.
The durable one-write marker and retained key constraints prevent another
application invocation from writing the same key. No lock spans storage I/O.

File purge preparation similarly reads only immutable event-ID target identity
before taking the shared owner hierarchy lock, then freshly locks the matching
owned pending READY file row and validates key/version/purge timestamp and
pending-purge ancestry. It does not mutate the namespace or finalize metadata.
The resource transaction commits before live job-token renewal and DELETE; job
row locks never overlap hierarchy/file locks. Existing worker completion is fenced
in its own short transaction. A stale external effect is still possible under the
accepted [trusted-writer contract](../file-purge.md).

Real PostgreSQL/Garage tests cover competing writes, row-lock-before-admission,
resource/job lock release before storage I/O, stale claim rejection and late
effects without newer-object deletion. Existing hierarchy/request races remain
in the full verification suite. Metadata finalization/folder traversal will need
their own locking decisions and race coverage.

No performance benchmark currently exists, so this ADR makes no throughput claim.

## Phase 2D.2 Slice 2 progress and finalization

Resource progress transactions extend the order to **shared owner hierarchy lock,
fresh scalar file-row lock/read, work-row lock, job-row lock for final fencing**.
Only immutable V16 identity is read before hierarchy coordination. Scalar queries
avoid stale JPA first-level resource state. Job token/type/aggregate and
PostgreSQL-clock lease checks after the final job lock and at the final write roll
back stale work, byte receipts and metadata removal. Existing job-only operations
retain their protocol; no path takes hierarchy/resource locks while holding a job
lock first. Garage I/O occurs only after preparation commits and before receipt
recording begins.

Standalone metadata finalization requires an exact captured owner/folder/key/
version/READY/lifecycle match and a durable byte receipt. Every pending folder
ancestor defers removal until a future manifest can prove sealed discovery. The
shared hierarchy lock coordinates this check/removal with exclusive folder purge
requests in both winner orders. Byte deletion from the original standalone intent
may continue beneath that barrier. Historical-success recovery uses the same
order and finalizer, with locked authoritative SUCCEEDED status replacing a live
claim; it never rearms that job. PostgreSQL/Garage tests cover both folder winner
orders, duplicate finalizers, cached stale rows, rollback and lease expiry during
job-lock waits. See [file purge](../file-purge.md).
