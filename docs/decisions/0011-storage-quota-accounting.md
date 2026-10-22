# ADR-0011: MVP logical storage quota

[Copy M1](0012-safe-file-copy.md) extends admission with a full-size independent
UPLOADING reservation using the same atomic trigger. READY transfers its charge;
uncertainty/evidence alone releases nothing. Permanent logical cancellation or
folder disposal releases charge through FAILED/removal while retaining physical
uncertainty. No source charge or prior allocation identity is changed.

- Status: Implemented
- Date: 2026-10-21

## Decision

Each user owns a PostgreSQL `storage_quotas` row with a default ten-GiB limit.
Existing accounts are backfilled from file metadata; an account already above the
default is grandfathered at its current charge. An Editor upload into a shared
folder charges the folder/file owner, never the initiator. The client cannot
select the owner or limit.

READY file `size_bytes` sum is **used**. UPLOADING file `size_bytes` sum is
**reserved**. FAILED files are neither. **Available** is
`max(0, limit - used - reserved)`; lowering a limit below existing usage does not
destroy files, but further capacity-consuming admissions fail. Trash, pending
purge and files under trashed/pending folders stay charged until each file's
metadata is durably removed. Restore, rename and move do not change charge.
READY moves the same length from reserved to used once. Cancel/expiry release an
UPLOADING reservation only when eligible FAILED commits. Completion decisions,
receipts, unknown outcomes, abort acknowledgement and elapsed expiry alone
release nothing.

V38 initializes counters, captures immutable known allocation lengths and
maintains counters with an AFTER file-row trigger. The trigger locks the owner's
quota row and rejects new net consumption above the limit. Rollback covers file,
allocation/session and counter together. Session replay inserts no second file
and cannot reserve twice. The trigger also covers historical finalization and
folder purge metadata removal. V39 makes the quota row follow deletion of its
owner account after owned file metadata has been removed.

Retained known allocation length supplies a separate **uncertain storage** inventory:
write-admitted allocations without a currently READY file. It includes
FAILED/removed allocations with possible parts or objects. Unknown historical
lengths are counted separately. This logical length estimate can overlap reserved
bytes and is neither actual Garage consumption nor proof of reclamation. No quota
release depends on Garage absence, DELETE/Abort acknowledgement, age or GC.

## Coordination and repair

Reservation takes owner hierarchy, the existing owner/idempotency advisory key,
then inserts a new file; its AFTER trigger locks the quota row before the new
allocation and session rows are inserted. There are no existing resource rows to
reacquire on that path. READY/FAILED and removal paths retain their hierarchy
and fresh file/allocation/session/progress order, with the quota row locked by
the file transition trigger. Folder removal uses exclusive hierarchy coordination
across its bounded page. No quota lock spans Garage I/O. Concurrent new files
for one owner serialize on the quota row even with different idempotency keys.

Internal reconciliation enumerates at most 100 owners per keyset page. Each
owner repair uses its own ten-second transaction with three-second statement
and two-second lock-wait bounds. It takes exclusive hierarchy then quota row
coordination, reads a fresh READY/UPLOADING aggregate and optionally replaces
only derived counters. It never changes a limit, lifecycle state, allocation,
receipt, purge proof or Garage assessment. Timeouts leave counters unchanged.
This is explicit repair, without autonomous scheduling or physical cleanup.
The owner quota row may be a write-contention point; no performance claim is made.

Quota exhaustion returns a generic 409 `CONFLICT` after normal destination
authorization, without another owner's quota details. The authenticated API
reports only the caller's usage. `uncertainStorageBytes` must be read with
`unknownSizeAllocations` because some retained allocations lack lengths.
