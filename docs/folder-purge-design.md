# Phase 2D.2: Safe recursive folder purge and metadata finalization

Status: Approved implementation design; implementation in progress.
Phase 2D.2 Slice 1 completed in commit `8ca8d9b`.
Phase 2D.2 Slice 2 completed and verified on 2026-10-13.
Slices 3–6 remain pending.

Recommend **one durable folder job, a paged subtree manifest, and shared per-file deletion progress**. Keep the existing trusted-writer contract, v1 events, RabbitMQ topology, and storage adapter. Add explicit continuation handling so successful batches do not exhaust the retry budget.

The current [file handler](../backend/src/main/java/com/vaultdrive/worker/FilePurgeJobHandler.java) uses shared file progress and conditional metadata finalization; [V16](../backend/src/main/resources/db/migration/V16__persist_file_storage_identity_and_purge_targets.sql) retains immutable targets and allocations. Folder execution and subtree metadata removal remain unimplemented.

This design follows [ADR-0008](decisions/0008-transactional-outbox-rabbitmq.md), [ADR-0007](decisions/0007-concurrency-locking.md), [file purge](file-purge.md), [background jobs](background-jobs.md), and [job workers](job-workers.md).

## Subtree ownership and traversal

1. **Freeze membership at request commit.** Preserve the exclusive owner hierarchy lock and existing ancestor/descendant purge checks. Atomically commit the root's `purge_requested_at`, one root event, and a small purge-run identity record containing owner, root, event ID, and request timestamp. No subtree snapshot belongs in the API transaction.

   The committed root marker prevents descendants from moving out, resources from moving in, uploads from being reserved, and restores from escaping through root fallback. Membership is stable; previously admitted uploads can still finish their lifecycle transition.

2. **Discover the complete subtree incrementally.** Persist a folder frontier with parent identity, child/file keyset cursors, and enumeration-complete flags. Enumerate immediate children and files in fixed-size pages, including active descendants, separately trashed descendants, and every upload status. Commit inserted members and cursor advancement together.

   Retain all folder rows during discovery. Defer file metadata removal beneath the root until discovery is complete, so concurrent standalone finalization cannot make enumeration skip an accepted descendant. Existing byte deletion may continue.

3. **Seal discovery before folder-led deletion.** Completion requires every recorded folder's child and file scans to be exhausted. Validate owner relationships and reject inconsistent or cyclic membership. The durable manifest then accounts for the complete subtree without a recursive in-memory walk.

The existing [`hasPendingPurgeInSubtree`](../backend/src/main/java/com/vaultdrive/folder/FolderRepository.java) performs a whole-subtree recursive query. Paging workers does not bound that API query. Retain its correctness initially, but impose transaction/statement and lock-wait limits; an oversized check must roll back without accepting intent.

## Storage targets and upload eligibility

Use a shared per-file work record, unique by file ID, containing immutable target identity and monotonic deletion/finalization receipts.

- For an existing standalone purge, reuse and verify its V16 target.
- For an inherited folder purge, copy namespace, endpoint, region, bucket, and key from `file_storage_objects`; also capture owner, folder, version, status, and lifecycle timestamps.
- Persist targets before storage deletion and before metadata removal. Never resolve them from current configuration, display paths, HEAD responses, or reconstructed keys.
- Keep V16 allocations permanently. Keep targets, progress, and job identities after metadata removal.

Eligibility needs an explicit distinction:

| File state | Safe action |
|---|---|
| `READY`, verified allocation/target | Delete using the immutable target. |
| Verified allocation with `write_started = false` | Under fresh hierarchy/file/allocation coordination, establish that no write was admitted; metadata can be finalized without DELETE. Subsequent `beginWrite` must fail. |
| `UPLOADING` or `FAILED`, write already admitted | Block automatic purge until writer completion/quiescence is independently established. |
| Unknown legacy binding | Block; no inferred target or deletion. |

`FAILED` alone is insufficient: the current upload path can record failure while a timed-out PUT may still finish. Neither age nor the purge lease proves that writer has stopped. The smallest reliable Phase 2D.2 design therefore completes verified READY/never-written subtrees and retains unsafe subtrees for later reconciliation or approved repair.

## Concurrent operations and lock order

Preserve both parent/child folder-purge winner orders: the first committed request wins; the second receives `404`. Do not merge overlapping folder intents.

An existing file purge is different: parent folder requests currently permit separately pending file purges. Attach that file's existing target/work record to the folder manifest. Either authorized job may establish the same byte-deletion receipt; the folder job can continue even if the standalone job is terminal `FAILED`, without resetting that job.

Replace the file handler's blanket pending-purge ancestry rejection with narrowly validated participation in the recorded folder run. Before membership exists, permit byte deletion from the original verified file intent, but defer metadata finalization.

For new progress/finalization transactions, document this order:

**owner hierarchy lock → fresh root/resource rows → work/progress rows → job row for the final fence.**

Use shared hierarchy mode for individual file work and exclusive mode for folder removal or structural manifest transitions. Order multiple row locks deterministically. Check the job token and PostgreSQL-clock lease after acquiring the job lock and at the write; a failed fence rolls back the entire transaction. Never acquire hierarchy/resource locks while already holding a job lock.

This explicitly extends ADR-0007/0008: existing job-only operations remain unchanged, but resource checkpoints now need atomic claim validation. No database transaction or lock spans Garage I/O.

**Slice 1 completed (`8ca8d9b`): Folder restore eligibility**

`FolderService.restoreFolder` now explicitly rejects restoration
when the folder's own permanent purge is pending, using the existing
404 semantics. Ownership checks, ancestor-purge checks, and lock
ordering are preserved. V11 remains the final database guard.

Regression tests cover direct rejection, both concurrent
restore/purge winner orders, and database constraint enforcement.

Validation: 93 focused tests passed; 499 full-suite tests passed,
7 environment-gated tests skipped, and zero failures.

A separate Open EntityManager in View transaction-joining issue
was observed during exploratory HTTP testing and remains under
investigation.

## Execution, retries, and finalization

Use these durable phases:

`DISCOVER → DELETE_FILES → DELETE_FOLDERS → FINALIZED`

Each invocation performs bounded database pages and bounded storage operations, checks cancellation between units, and yields before its runtime limit.

A parent job avoids creating one child job/event per descendant and avoids dependency scheduling. It sacrifices intra-subtree parallelism, which is appropriate for the smallest implementation; unrelated roots still use existing worker capacity.

The current [`JobHandler.Result`](../backend/src/main/java/com/vaultdrive/worker/JobHandler.java) supports only success/retry, and claims consume ten total attempts. Add fenced `CONTINUE` handling:

- Successful continuation returns the same job to due `PENDING` without consuming failure budget.
- Keep a monotonic claim count separate from bounded failed/abandoned execution accounting.
- Real failures retain backoff and terminal exhaustion.
- Crashes, cancellation, and lease abandonment still consume failure budget.
- Require durable progress for normal continuation, preventing endless empty yields.

For each object: prepare/validate briefly, commit, renew/check the claim, DELETE outside transactions, then record a fenced receipt. Preserve current absence/error classification and binding checks.

File metadata can disappear only after a verified deletion/absence receipt—or the coordinated never-written proof—and a fresh match against captured ownership/lifecycle state. Commit removal and its finalization receipt together.

Delete folders bottom-up in bounded batches. Require completed enumeration, finalized direct files, and no remaining child-folder rows. Existing non-cascading foreign keys remain the final guard. Delete the root last and atomically mark the run finalized. A retry encountering a missing root succeeds only when that durable finalization record proves completion.

## Integrating standalone file finalization

Use the same file finalizer for standalone and folder work:

- Newly executed file jobs persist byte receipts and finalize when permitted.
- Under a discovering folder run, retain metadata until its manifest is sealed.
- Retries after metadata removal consult matching durable receipts; missing metadata alone is never success.
- A bounded recovery scan finalizes pre-2D.2 `SUCCEEDED` file jobs using their matching V16 target and authoritative job outcome. Do not reset or reinterpret those jobs as unfinished.

Continue documenting file `SUCCEEDED` as proof of byte deletion. Use a separate finalization marker so historical successes remain meaningful.

### Slice 2 implementation and recovery

[V17](../backend/src/main/resources/db/migration/V17__create_shared_file_purge_work.sql)
adds `file_purge_work`, with file-ID primary key, unique retained key, immutable
owner/folder/version/status/lifecycle snapshot and exact verified storage binding.
Nullable `bytes_deleted_at` plus `byte_receipt_job_id` establish byte success;
`metadata_finalized_at` is separate and requires byte proof. Constraints and an
update trigger prohibit identity changes and receipt clearing/replacement. No
resource or job foreign key can cascade away work. V16 is untouched.

[FilePurgeProgressService](../backend/src/main/java/com/vaultdrive/worker/FilePurgeProgressService.java)
prepares verified pending READY work, records byte receipts after Garage I/O,
and separately finalizes freshly matching metadata. These are independent short
transactions, with hierarchy/resource/work/job lock order and PostgreSQL-clock
fencing. Finalization checks owner, folder, storage key, version, READY state and
all captured lifecycle timestamps. It atomically removes metadata and establishes
its receipt. Original standalone byte deletion may proceed beneath pending
folder ancestry, but every pending ancestor defers metadata removal. No manifest
or discovery-sealed exception exists in Slice 2.

A lost byte receipt replays the immutable DELETE; a committed receipt skips I/O
on retry. A lost metadata transaction rolls back both removal and finalization
receipt. A lost job completion after removal replays from retained receipts.
Missing metadata alone never proves byte success or establishes a finalization
receipt. Missing metadata with byte proof can finish the byte job while remaining
unfinalized for review.

[FilePurgeRecoveryService](../backend/src/main/java/com/vaultdrive/worker/FilePurgeRecoveryService.java)
provides explicit UUID-keyset recovery pages of 1–100 historical `SUCCEEDED`
file jobs with matching V16 targets. Each candidate independently verifies
allocation/work identity, locks the successful job last, imports authoritative
byte success and applies the same finalizer. No job status, attempts or timestamps
change; no Garage call or inferred target is used. Missing/changed historical
metadata cannot create a work snapshot. Blocked candidates do not starve later
pages; restart from the beginning on a subsequent pass. Recovery is not scheduled
or exposed as an HTTP API. See [file purge recovery](file-purge.md).

This slice does not implement folder runs/manifests, subtree discovery,
never-written inherited-file proof, folder execution/removal or CONTINUE semantics.
Workers, consumers and publisher retain disabled defaults and v1 contracts.

Validation: 96 focused tests passed, including 44 PostgreSQL/Garage file-purge
tests. The full safely runnable suite ran 532 tests: 525 passed, 7 environment-gated
RabbitMQ tests skipped, zero failures/errors. Fixtures used disposable unique
storage objects; no buckets, infrastructure or existing Garage objects were
changed. This is deterministic crash-boundary/rollback coverage, not a real
process-kill experiment. The broker protocol was unchanged and not reverified live.

## Failure-mode analysis

| Failure | Recovery / invariant |
|---|---|
| Crash during discovery | Resume committed frontier/cursors; replayed inserts are idempotent. |
| DELETE succeeds, receipt commit is lost | Repeat the same immutable DELETE. |
| Receipt commits, metadata removal fails | Retry finalization without needing another DELETE. |
| Metadata removal commits, job completion is lost | Durable receipt/run state allows replay to finish. |
| Lease expires or stale worker returns | Reject stale database progress; late DELETE may repeat only the retained target. |
| Storage binding drift, access failure, or uncertain response | Retain metadata and intent; retry within budget, then fail visibly. |
| Unsafe writer or legacy target | Preserve the root barrier and metadata; require verified recovery. |
| Final attempt is abandoned | Existing recovery records terminal failure; never clear intent automatically. |

These guarantees depend on permanent key nonreuse, no replacement of finalized content, and stable storage bindings. They do not introduce storage-side fencing.

Legacy standalone requests missing V16 targets remain blocked even if today's allocation/configuration appears usable. Existing folder events may initialize a run only after matching committed outbox identity and current root intent; descendants still require verified allocations. Requests predating the outbox remain outside automatic backfill.

## Recommended implementation order

| Slice | Changes and acceptance evidence |
|---|---|
| 1. Restore eligibility — COMPLETE (`8ca8d9b`) | Rejects own pending folder purge as `404`; concurrency and regression tests passed. |
| 2. Shared file progress/finalization — COMPLETE | V17 identity/receipts, standalone finalization and bounded historical recovery; rollback, stale/expired fencing, missing-row replay, retained allocations and concurrent folder guards verified. |
| 3. Bounded continuation | Extend worker/service accounting; prove healthy work beyond ten batches completes, while ten failures/abandonments exhaust. |
| 4. Durable subtree discovery | Add run, folder frontier, and file membership records; test paged restart, complete inclusion, nested intents, moves/restores, and finalization-versus-discovery races. |
| 5. Folder byte execution | Register the folder handler; test existing file-job overlap, partial DELETE recovery, unsafe uploads, legacy bindings, and binding drift. |
| 6. Bottom-up removal | Test FK guards, blocked descendants, stale finalizers, root-last deletion, and crash after root removal before job completion. |

Use append-only migrations after V16 for progress/manifest tables, uniqueness and immutable/monotonic guards, cursor/work indexes, and revised job accounting. Keep V16 targets unchanged. No HTTP payload/status contract change beyond correcting restore eligibility; no v1 event change, descendant messages, or RabbitMQ topology change.

Keep batches, execution budgets, database waits, and queues bounded. Memory should scale with page size; durable manifest storage necessarily scales with subtree size. Batch tuning and throughput remain unmeasured.

The original design review was read-only. Slice 1 and the Slice 2 implementation
are documented above; Slices 3–6 remain planned. Test execution results above
describe repository verification, not production enablement or load measurements.
