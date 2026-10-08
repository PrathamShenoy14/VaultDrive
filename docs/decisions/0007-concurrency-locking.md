# ADR-0007: Transaction-scoped hierarchy coordination

- Status: Accepted; folder-side migration implemented, file-side migration pending
- Date: 2026-10-09

## Context

Create, rename, move, and restore make a check-then-write decision inside an owner/folder namespace. Unique indexes prevent invalid commits, but concurrent requests also need predictable state transitions and understandable errors. Restore and permanent-delete additionally compete for the same eligible Trash state.

## Decision

Introduce an owner-scoped hierarchy coordinator backed by PostgreSQL transaction advisory locks. It exposes shared and exclusive modes, requires an active transaction, and executes on the connection enlisted in the current JPA transaction. PostgreSQL therefore releases the lock on commit or rollback.

The signed `bigint` key has a stable derivation: fixed namespace `0x5641554C54445256` (ASCII `VAULTDRV`) XOR the UUID most-significant 64 bits XOR the UUID least-significant 64 bits rotated left by one. Reducing 128 bits to 64 can collide; a collision only causes conservative extra serialization. The formula must not change without a coordinated deployment because mixed versions must contend on the same key.

Folder create, rename, move, trash, and restore acquire the exclusive hierarchy lock before any protected read. They remain PostgreSQL-only operations, so the advisory lock is never held across Garage I/O.

This is intentionally a staged migration. File operations still acquire the pessimistic write lock on the owner's `users` row. Rename, move, and trash also load a file before the metadata service obtains that row lock. Until file operations migrate to the coordinator with lock-before-read where required, folder and file paths do not mutually exclude each other and the system must not claim complete cross-path concurrency safety.

## Consequences

- All folder mutations for one owner still serialize; different owners normally proceed independently.
- Shared mode is available for a later file-side migration but is not yet used by production paths.
- A rare derived-key collision can serialize different owners without weakening correctness.
- The existing file restore and permanent-delete paths retain their user-row lock-before-read ordering, but that lock does not coordinate with the folder advisory lock.
- File rename, move, and trash require lock-before-read repair as part of their migration.
- Database constraints remain mandatory after any lock refactor.

## Verification and remaining migration

- PostgreSQL integration tests cover shared/shared compatibility, shared/exclusive blocking, exclusive/exclusive blocking, rollback release, and the transaction requirement.
- The existing concurrent folder-restore integration test exercises the migrated folder path.
- Migrate file operations to the same coordinator without holding a transaction lock over Garage calls.
- Add PostgreSQL-backed folder/file and restore/permanent-delete race coverage after both paths share the coordinator.
- A reproducible baseline and post-change contention measurement.

No performance benchmark currently exists, so this ADR makes no throughput claim.
