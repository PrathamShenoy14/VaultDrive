# ADR-0007: Transaction-scoped hierarchy coordination

- Status: Accepted; folder operations and file rename/move/trash implemented
- Date: 2026-10-09

## Context

Create, rename, move, and restore make a check-then-write decision inside an owner/folder namespace. Unique indexes prevent invalid commits, but concurrent requests also need predictable state transitions and understandable errors. Restore and permanent-delete additionally compete for the same eligible Trash state.

## Decision

Introduce an owner-scoped hierarchy coordinator backed by PostgreSQL transaction advisory locks. It exposes shared and exclusive modes, requires an active transaction, and executes on the connection enlisted in the current JPA transaction. PostgreSQL therefore releases the lock on commit or rollback.

The signed `bigint` key has a stable derivation: fixed namespace `0x5641554C54445256` (ASCII `VAULTDRV`) XOR the UUID most-significant 64 bits XOR the UUID least-significant 64 bits rotated left by one. Reducing 128 bits to 64 can collide; a collision only causes conservative extra serialization. The formula must not change without a coordinated deployment because mixed versions must contend on the same key.

Folder create, rename, move, trash, and restore acquire the exclusive hierarchy lock before any protected read. They remain PostgreSQL-only operations, so the advisory lock is never held across Garage I/O.

File rename, move, and trash acquire the shared hierarchy lock before loading current file or ancestor state. Each performs fresh validation and flushes inside that short transaction. Because shared locks allow file mutations for the same owner to overlap, the file row has an optimistic version; a stale same-file write returns `409 Conflict` instead of silently overwriting another mutation.

This remains a staged migration. Upload, restore, permanent-delete request, upload finalization, and future workers retain their existing coordination and are not claimed to coordinate with folder structural operations.

## Consequences

- All folder mutations for one owner still serialize; different owners normally proceed independently.
- Shared mode coordinates file rename, move, and trash with folder structural operations while allowing unrelated file metadata mutations to overlap.
- A rare derived-key collision can serialize different owners without weakening correctness.
- The existing file restore and permanent-delete paths retain their user-row lock-before-read ordering, but that lock does not coordinate with the folder advisory lock.
- Upload and metadata finalization also remain outside the hierarchy coordinator.
- Database constraints remain mandatory after any lock refactor.

## Verification and remaining migration

- PostgreSQL integration tests cover shared/shared compatibility, shared/exclusive blocking, exclusive/exclusive blocking, rollback release, and the transaction requirement.
- The existing concurrent folder-restore integration test exercises the migrated folder path.
- PostgreSQL-backed races cover file move versus folder trash, rename versus folder trash, and same-file optimistic conflicts.
- Migrate remaining competing file lifecycle paths without holding a transaction lock over Garage calls.
- Add restore/permanent-delete and other lifecycle race coverage when those paths share the coordinator.
- A reproducible baseline and post-change contention measurement.

No performance benchmark currently exists, so this ADR makes no throughput claim.
