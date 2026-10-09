# ADR-0005: File upload and trash lifecycle

- Status: Accepted and implemented
- Date: 2026-10-09 (retrospective record)

## Context

File operations span relational metadata and object storage. Users need listing, download, rename, move, Trash, and restore while incomplete uploads remain invisible.

## Decision

Use `UPLOADING`, `READY`, and `FAILED` for storage readiness. Reserve metadata as `UPLOADING` after coordinated destination validation, stream to object storage outside database transactions and hierarchy locks, then conditionally mark `READY` only after coordinated fresh destination validation. A destination made inaccessible during transfer causes a durable conditional `FAILED` transition followed by best-effort object cleanup. A cleanup failure leaves tracked bytes behind; an uncertain database finalization outcome retains the object because deleting it could remove bytes for a committed READY row. Expose only active `READY` files. Represent Trash with `deleted_at`, leaving the Garage object and storage key unchanged. Restore the same row/object to its original accessible folder or root and resolve name collisions with extension-preserving suffixes. Restore acquires the shared owner hierarchy lock before a fresh eligible-row lock and ancestry validation, then serializes name allocation within the chosen destination.

## Consequences

- Incomplete uploads do not appear as usable files.
- User-visible metadata operations avoid object copies.
- Trash/restore are fast database operations and preserve bytes.
- A successful object upload followed by uncertain metadata finalization may leave stale `UPLOADING` metadata or an orphan; reconciliation is required but not implemented.
- Trashing releases the active name, so restore may need an automatic new name.
- Folder accessibility must be checked in addition to file ownership because a file below a deleted ancestor is not accessible.

## Alternatives considered

- Upload object first, then create metadata: avoids `UPLOADING` rows but creates untracked objects on database failure.
- Store object state and user lifecycle in one enum: fewer columns, but conflates independent state machines.
- Copy/delete objects on rename or move: unnecessary because storage keys are intentionally independent of display paths.
