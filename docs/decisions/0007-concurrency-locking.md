# ADR-0007: Transaction-scoped hierarchy coordination

- Status: Accepted; folder operations and current file metadata mutation paths implemented
- Date: 2026-10-09

## Context

Create, rename, move, and restore make a check-then-write decision inside an owner/folder namespace. Unique indexes prevent invalid commits, but concurrent requests also need predictable state transitions and understandable errors. Restore and permanent-delete additionally compete for the same eligible Trash state.

## Decision

Introduce an owner-scoped hierarchy coordinator backed by PostgreSQL transaction advisory locks. It exposes shared and exclusive modes, requires an active transaction, and executes on the connection enlisted in the current JPA transaction. PostgreSQL therefore releases the lock on commit or rollback.

The signed `bigint` key has a stable derivation: fixed namespace `0x5641554C54445256` (ASCII `VAULTDRV`) XOR the UUID most-significant 64 bits XOR the UUID least-significant 64 bits rotated left by one. Reducing 128 bits to 64 can collide; a collision only causes conservative extra serialization. The formula must not change without a coordinated deployment because mixed versions must contend on the same key.

Destination namespace locks use PostgreSQL's separate two-`integer` advisory-lock key space: fixed class `0x56444E53` plus a stable 32-bit mix of owner and destination UUIDs, with `null` representing root. Hash collisions only add conservative serialization.

Folder create, rename, move, trash, and restore acquire the exclusive hierarchy lock before any protected read. They remain PostgreSQL-only operations, so the advisory lock is never held across Garage I/O.

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
- PostgreSQL-backed races cover file move versus folder trash, rename versus folder trash, same-file optimistic conflicts, upload reservation versus folder trash, trash during the transfer gap, competing finalization transitions, both restore/permanent-delete winner orders, restore versus folder trash/move, and same-name restores.
- Add future purge/reconciliation race coverage when those workers are implemented.
- A reproducible baseline and post-change contention measurement.

No performance benchmark currently exists, so this ADR makes no throughput claim.
