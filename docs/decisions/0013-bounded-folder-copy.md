# ADR-0013: bounded owner-only folder copy

- Status: Implemented
- Date: 2026-10-22

Copy M2 composes [ADR-0012](0012-safe-file-copy.md) rather than implementing another
storage protocol. V43 retains owner/key-unique jobs, a complete immutable bounded
snapshot, independent destination UUIDs and one linked M1 operation per file.
Snapshot admission is one exclusive owner-hierarchy transaction with at most
1,000 members and depth 64. Existing hierarchy writers are excluded until commit.
A larger source fails before admission; resumable discovery of arbitrarily large
changing trees would need another snapshot/versioning contract and is deferred.

Folders are materialized at admission; files/allocations are reserved incrementally
using M1. Ordinary resource resolution and scalar folder mutation checks hide the
whole staging tree. Only private M1 destination eligibility can authorize work in
that tree, and only for a linked active owner job. A terminal job decision prevents
new dispatch/finalization. Existing lifecycle/ancestry/purge eligibility remains.
Every destination receives a new independent key/allocation; no grants or links
are copied. Source mutation cannot change names, membership or destination IDs.
Unavailable predispatch source members fail safely rather than being replaced.

Explicit execute/recover endpoints advance at most four member steps, checking a
20-second work window between steps. Every transaction/provider call retains M1's
bounds; no transaction or hierarchy lock spans I/O. Recovery never reserves a new
file or redispatches CopyObject. A committed RESERVED operation can be executed;
a committed UNKNOWN dispatch can only gain positive evidence. An abandoned
caller requires no lease takeover. PostgreSQL checkpoints derive from durable
M1 READY facts and only move forward. Concurrent callers converge on M1's single
committed dispatch permission. There is no new scheduler, broker or workflow engine.

Folder admission/checkpoint/cancel/discard/publication use exclusive owner hierarchy
before mutable reads. M1's file/allocation/operation/namespace coordination precedes
job progress writes. M1 execution retains its existing shared hierarchy order.
No path locks a job then waits for resources without first excluding resource
writers through exclusive hierarchy. PostgreSQL guards complete membership once
at admission commit, validates each mapping on insertion, freezes membership and
operation linkage, and rejects checkpoint/terminal regression or premature
publication. Revalidating the entire snapshot in every deferred member trigger
was rejected after the maximum-size test exposed unbounded commit work.

All-file READY and positive receipts precede atomic whole-tree publication and
folder COPIED history. A rollback leaves the tree hidden and M1 receipts reusable.
File COPIED history still describes the independently committed M1 file operation,
not overall job success. Both use the admission owner's frozen actor display name.
No folder creation/copy event is emitted for staging or unsuccessful jobs.

Quota is incremental rather than a whole-tree reservation. Every M1 reserve is
atomic with linkage, and replay cannot double charge. Hidden READY files count as
used; unfinished reservations stay reserved. Exhaustion blocks further work without
undoing existing progress. Cancellation closes publication permanently and retains
all charges/evidence. Separate idempotent discard accepts the existing bounded
folder purge, with its independent logical-disposal/physical-deletion proof and
metadata-removal accounting. Parent pending purge owns its sealed subtree; discard
cannot introduce overlapping intent. Uncertain admitted bytes are never erased
or reclassified by cancellation, age, negative probes or checkpoint repair.

This deliberately trades a finite admission cap, retained partial quota and
explicit client/operator continuation for a small explainable recovery protocol.
There is no source pinning, arbitrary-size snapshot, automatic uncertain-object
reclamation, process-kill test harness, retention policy or performance claim.
See [API, operational procedure and limits](../folder-copy.md).
