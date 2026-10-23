# ADR-0005: File upload and trash lifecycle

Current consolidation: [ADR-0010](0010-backend-consolidation.md) retires synchronous new-upload admission/PUT and foreground receipt creation. Historical phase descriptions below remain records of their original revisions; retained receipts, recovery and locking guarantees still apply. Current nonempty uploads use direct multipart; [ADR-0016](0016-zero-byte-files.md) adds narrow zero-byte SERVER_PUT creation.

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

## Phase 2E.5B3 superseding upload completion contract

The foreground now commits durable success evidence after PUT and input closure,
then uses conservative receipt-based READY finalization. Inaccessible destinations
remain blocked UPLOADING with retained bytes; the previous foreground FAILED plus
best-effort DELETE behavior is superseded. Ordinary PUT/close failure still attempts
FAILED. Explicit recovery revalidates receipts and eligibility, never resurrects
FAILED and never repeats PUT, HEAD or DELETE. A crash before the receipt commit
remains uncertain; historical admissions are not backfilled. See
[the exact identity, boundaries and remaining gap](../upload-success-recovery.md).

## Phase 3E / M3 direct multipart completion

The separately labelled DIRECT_MULTIPART protocol now freezes an authoritative
part manifest, dispatches completion at most once and commits its own immutable
receipt before a freshly eligible READY CAS. Explicit recovery uses strict positive
object evidence or the retained receipt, never another uncertain Complete. V25
PUT receipts, V26 cleanup and existing physical purge proofs do not adopt multipart,
including READY multipart files. The synchronous contract above is unchanged.
See [completion and recovery conditions](../multipart-completion-recovery.md).

## M5 multipart namespace and purge extension

[M5](../multipart-purge-compatibility.md) supersedes the M3 blanket multipart removal
fence. Eligible cancel/expiry atomically transitions UPLOADING to FAILED once,
releasing its active name while retaining storage uncertainty. READY files use
protocol-specific completion receipts with existing physical deletion/folder
barriers. Unfinished folder members use a separate logical-discard receipt, never
legacy PUT, byte or never-written proof. File/session reads after folder removal
remain inaccessible; already permitted storage facts cannot resurrect metadata.
