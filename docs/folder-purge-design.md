# Phase 2D.2: Safe recursive folder purge and metadata finalization

Copy M2 adds a separate owner-authorized discard admission for FAILED/CANCELLED
hidden copy trees. It records normal folder purge intent/outbox/run and returns
that deletion operation ID; all discovery, sealed snapshots, bounded worker phases,
COPY byte/logical-discard proof and finalization rules below are reused unchanged.
An existing parent pending purge owns the subtree and prevents overlapping discard.
Cancellation alone never frees quota or proves uncertain bytes gone. See the
[folder-copy operational procedure](folder-copy.md).

Copy M1 extension: READY COPY members use their own completion receipt before
normal exact-key byte deletion. Unfinished COPY members use copy_discarded_at,
separate from multipart logical, byte and never-written receipts. The worker
locks copy progress after allocation and before work/member/job, and commits
permanent CANCELLED publication authority with logical proof before its final
job fence. It preserves the sealed file snapshot and reservation until metadata
removal. Final removal checks that proof without acquiring a new copy-progress
lock after the job fence. Late positive facts can be retained after removal but
cannot resurrect metadata. Logical completion makes no reclamation claim; never
repeat an uncertain CopyObject or delete its possible late object. See
[copy recovery](file-copy.md) and [ADR-0012](decisions/0012-safe-file-copy.md).

Current API update: [MVP API contract consistency](api-contract.md) supersedes
historical capability, folder/Trash pagination, deletion-polling and error-envelope
descriptions below. Storage, authorization and worker protocols are unchanged.

M5 extends this legacy purge design with [multipart compatibility](multipart-purge-compatibility.md):
READY requires multipart completion evidence; unfinished folder members use retained
logical-discard proof. Folder success does not promise physical reclamation of
those unfinished allocations.

Status: Approved implementation design; implementation in progress.
Phase 2D.2 Slice 1 completed in commit `8ca8d9b`.
Phase 2D.2 Slice 2 completed and verified on 2026-10-13.
Phase 2D.2 Slice 3 completed and verified on 2026-10-13.
Slices 4–5 implement durable paginated discovery and folder-led file byte deletion.
Slice 6 implements bottom-up folder removal and durable root finalization.

Recommend **one durable folder job, a paged subtree manifest, and shared per-file deletion progress**. Keep the existing trusted-writer contract, v1 events, RabbitMQ topology, and storage adapter. Add explicit continuation handling so successful batches do not exhaust the retry budget.

The current [file handler](../backend/src/main/java/com/vaultdrive/worker/FilePurgeJobHandler.java) uses shared file progress and conditional metadata finalization; [V16](../backend/src/main/resources/db/migration/V16__persist_file_storage_identity_and_purge_targets.sql) retains immutable targets and allocations. Folder byte execution is implemented in Slice 5; Slice 6 adds subtree metadata removal.

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

Slice 3 extends [`JobHandler.Result`](../backend/src/main/java/com/vaultdrive/worker/JobHandler.java) with fenced `CONTINUE` handling:

- Successful continuation returns the same job to `PENDING`, due after one second, without consuming failure budget.
- Keep a monotonic claim count separate from bounded failed/abandoned execution accounting.
- Real failures retain backoff and terminal exhaustion.
- Crashes, cancellation, and lease abandonment still consume failure budget.
- Require a newer committed job-local checkpoint than the claim baseline, preventing endless empty yields.

### Slice 3 continuation foundation

V18 widens `attempt_count` to a monotonic BIGINT claim count and adds bounded
`failure_count`, `progress_version` and `claim_progress_version`. Existing terminal
outcomes and deadlines remain intact; historical failures are prior claims minus
one unresolved RUNNING or successful SUCCEEDED execution. Failure/abandonment
ordinals drive the unchanged one-to-sixty-second backoff and ten-failure limit.
Monotonic guards prohibit counter resets; all applied V14–V17 migrations remain intact.

For future folder handlers, atomically advance a useful durable cursor/member,
receipt, or phase/unit and call `BackgroundJobService.checkpoint(claim,
expectedVersion)` last in that same short transaction. Its MANDATORY transaction,
expected-version CAS, job-row lock and PostgreSQL-clock token/lease check make
stale/duplicate recording roll back the business work. Empty scans, heartbeats,
unchanged cursors and replayed receipts do not qualify. Actual folder cursor/work
schemas are deferred to Slice 4; this slice supplies the revision/fencing contract.
Resource/work locks precede the final job lock. No storage I/O spans a transaction.

CONTINUE uses an independent job-only transaction and requires progress newer
than the baseline captured on this claim. It releases the token and schedules the
same job one second later without resetting failures. Missing progress charges a
single NO_PROGRESS failure; stale tokens/expired leases change nothing. A crash
after checkpoint commit retains progress, consumes abandonment on recovery, and
the new claim must advance further or finish SUCCESS. Check cancellation between
units and use `remainingRuntimeNanos()` to yield before the local runtime deadline.
Late yields after cancellation are suppressed. Regular bounded polling admits
continuations; there is no immediate rescheduling path or new task queue.

Tests cover fifteen successful continuations then SUCCESS, pure and mixed
failure/abandonment exhaustion, committed-progress crash recovery, stale/duplicate
outcomes, atomic work/checkpoint rollback, no-progress rejection, PostgreSQL lease
expiry during a row-lock wait, legacy migration, concurrent workers/capacity and
runtime/shutdown cancellation. Verification on 2026-10-13: all 93 focused tests
passed, including 30 PostgreSQL job tests, 14 worker integration tests and 44
PostgreSQL/Garage file-purge regressions. The full safely runnable suite ran 545
tests: 538 passed, 7 environment-gated RabbitMQ tests skipped, zero failures/errors.
V17-to-V18 backfill, real PostgreSQL fencing/constraints and disposable Garage
fixtures passed. Read-only schema inspection confirmed the new column types,
constraints and monotonic-counter trigger. Compilation and `git diff --check`
passed. The broker contract was unchanged and not reverified live; no process-kill
or load claim is made.

The Slice 4 explicit discovery adapter now returns CONTINUE for committed pages.
It remains an explicit adapter; Slice 5 now calls it from the registered folder
handler. File SUCCESS/RETRY, Garage behavior, v1 events/topology and disabled
defaults remain unchanged. Recursive deletion and automatic recovery stay deferred.

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
| 3. Bounded continuation — COMPLETE | V18 separate claims/failures, fenced atomic checkpoint contract and delayed CONTINUE; PostgreSQL migration/fencing, worker cancellation/capacity and Garage regressions passed. |
| 4. Durable subtree discovery — COMPLETE | V19 retained run/frontier/file manifest, transactional UUID pages and fenced CONTINUE checkpoints; restart, complete inclusion, barriers, corruption and standalone-finalization races covered. |
| 5. Folder byte execution — COMPLETE | Registered handler, shared byte/never-written proofs, fenced member checkpoints and DELETE_FOLDERS; overlap, crash replay, unsafe bindings/writers and drift tested. |
| 6. Bottom-up removal - COMPLETE | V22 retained folder-removal receipts/sealed counts; shared file finalization, bounded leaf removal, atomic root/FINALIZED/checkpoint, stale/duplicate/crash/FK/corruption tests. |

Use append-only migrations after V16 for progress/manifest tables, uniqueness and immutable/monotonic guards, cursor/work indexes, and revised job accounting. Keep V16 targets unchanged. No HTTP payload/status contract change beyond correcting restore eligibility; no v1 event change, descendant messages, or RabbitMQ topology change.

Keep batches, execution budgets, database waits, and queues bounded. Memory should scale with page size; durable manifest storage necessarily scales with subtree size. Batch tuning and throughput remain unmeasured.

The original design review was read-only. Slice 1 and the Slice 2 implementation
and Slice 3 verification are documented above; Slices 4–5 are documented below.
Slice 6 remains planned. Test execution results above
describe repository verification, not production enablement or load measurements.

## Slice 4: durable paginated discovery

V19 adds retained `folder_purge_runs` keyed by the unchanged event/job UUID, with
owner, unique root, original request timestamp, phase and discovery seal. New API
requests commit intent, v1 outbox event and run together. The request transaction
does not snapshot the subtree beyond its existing overlap check. Its ten-second
transaction, five-second statement and two-second lock-wait limits roll back
oversized or contended requests without accepting partial intent.

`folder_purge_frontier` is the folder manifest: immutable owner/parent/root/trash
identity, separate UUID child/file cursors and completion flags, keyed by
`(event_id, folder_id)`. `folder_purge_files` is keyed by `(event_id, file_id)` and
retains parent, owner, observed version/status/lifecycle, opaque key, allocation
binding/write admission when known, and an existing standalone target's event ID.
File membership references its retained folder frontier. No resource, outbox or
job foreign key can cascade away these records. Unknown legacy allocations remain
nullable; discovery does not invent a binding or make unsafe uploads executable.
Lifecycle snapshots describe observation time, since previously admitted uploads
may still transition to FAILED under the existing upload rules.

`FolderPurgeDiscoveryService.discover` commits one immediate-child or immediate-file
page of 1–100 members. Indexed PostgreSQL UUID ordering with `id > cursor`, never
OFFSET, covers active and separately trashed folders/files and all upload statuses.
A full page does not prove exhaustion: an additional scan is required. Every page
commits inserts, cursor/completion advancement and the final V18 checkpoint CAS
together. Empty scans count as useful work only when they newly establish scan
completion. Replayed revisions, expired/replaced claims or job-identity mismatches
roll back the entire batch. Committed pages survive crashes; the next claim uses
the retained progress baseline and abandonment is charged once.

Discovery takes exclusive owner hierarchy coordination before fresh root/folder
reads, then run/frontier state, then the job lock for final fencing. Standalone
file progress/finalization retains shared hierarchy → fresh file → work → job
order. A batch holds no locks across external I/O. Bounds also apply to legacy
initialization and seal integrity queries. The explicit adapter checks cancellation
before/after pages and within member loops, rolls back checked interruption, and
refuses to start a ten-second unit near runtime exhaustion. It returns CONTINUE,
including after sealing, never purge SUCCEEDED. It is not a registered JobHandler,
HTTP endpoint or scheduler. Slice 5 registers the handler that invokes this adapter
and then processes sealed file membership.

Legacy folder events initialize only after matching the committed v1 outbox
identity, payload owner/root and exact root purge timestamp under coordination.
Missing events, pre-outbox requests, missing intent and mismatches fail closed.
No publication-status requirement is imposed: delivery may precede PUBLISHED.
Fresh parent/owner checks, uniqueness and repeated-edge rejection detect cycles
or inconsistent membership. Before sealing, bounded set-based integrity checks
also reject changed/missing recorded resources and unrepresented direct members.
All frontiers must have both scans complete before the atomic
`DISCOVER → DELETE_FILES` transition and seal/checkpoint commit. PostgreSQL guards
reject premature sealing, identity changes, cursor regression, completion reversal
and manifest writes after sealing. No folder-led DELETE or metadata removal occurs.

V17 standalone byte deletion can proceed throughout discovery. Metadata stays
until every pending ancestor has a matching sealed run and the file is represented
by an exact standalone event, ownership, parent, key, binding and lifecycle snapshot.
Legacy/unsealed/mismatched participation still defers. The hierarchy lock makes
this validation/removal atomic with discovery and request acceptance. A finalizer
that commits before acceptance removes a file outside the accepted membership;
a request that wins retains the file for discovery. Retained manifests survive
later standalone removal. SUCCEEDED continues to mean byte success only.

Recovery remains explicit: resume the same event/job/run and committed cursors;
do not reset counters, clear intent, recreate events or advance cursors manually.
At `DELETE_FILES`, hand off to the Slice 5 handler while retaining the root barrier.
Corruption, unknown bindings and unsafe writers require independently reviewed
repair; discovery success alone proves neither writer quiescence nor byte deletion.
The final integrity query can time out on a very large manifest; it safely retains
DISCOVER and needs future measured tuning. No throughput claim is made.

Verification on 2026-10-13: 213 focused tests passed, followed by 59 focused
discovery/job tests after final review (29 discovery tests). The final safely
runnable full suite ran 574 tests: 567 passed, seven environment-gated RabbitMQ
tests skipped, zero failures/errors. The skipped checks were three live outbox
broker tests and four live purge-consumer broker tests; v1/topology was unchanged.
Real PostgreSQL V19 migration/guards, paginated restart/fencing/races and existing
Garage file-purge regressions passed. New discovery fixtures perform no Garage
I/O; the existing storage suites used only disposable unique objects. No VM
infrastructure, credentials or existing Garage objects were modified. Compilation,
local documentation links and `git diff --check` passed. This proves deterministic
transaction/crash-boundary behavior, not real process-kill recovery or load capacity.

## Slice 5 folder-led file byte deletion

[FolderPurgeJobHandler](../backend/src/main/java/com/vaultdrive/worker/FolderPurgeJobHandler.java)
is registered for FOLDER_PURGE, with workers/consumers/publisher still disabled by
default. DISCOVER invokes the Slice 4 adapter; its sealing invocation returns
CONTINUE. DELETE_FILES selects at most 20 members in deterministic UUID order,
first unexamined members and then incomplete blocked members. Database queries
have five-second statement/two-second lock-wait limits and ten-second transaction
limits; pages and Java memory are bounded. Completion integrity checks may time
out on a very large manifest and fail closed, as discovery sealing does.

[FolderPurgeFileService](../backend/src/main/java/com/vaultdrive/worker/FolderPurgeFileService.java)
takes shared owner hierarchy coordination, validates the current root intent and
sealed owner/folder membership, locks the fresh file and original allocation,
then validates/locks V17 work. Existing metadata must exactly match observed
owner, parent, version, status, key and lifecycle timestamps. Missing metadata
requires matching retained work with verified proof; missing rows alone never
prove storage absence. Lifecycle drift since discovery blocks conservatively,
including an UPLOADING snapshot that subsequently becomes FAILED.

An inherited target is copied into immutable V17 work only after the V19 binding
exactly matches the original V16 allocation. No standalone event/child job is
invented. A separately pending file must also match its original V16 event-ID
target; a legacy request missing that target stays blocked. Existing shared work,
byte receipt and finalization receipt are reused without replacement. A folder
claim never locks, resets or depends on the outcome of a standalone job; even a
terminal FAILED file job can share byte success established by the folder job.
Standalone finalization retains the sealed-membership exception in DELETE_FOLDERS.

For READY with an admitted write, preparation commits, the live claim is renewed,
and the existing bounded adapter deletes the exact retained target outside every
transaction. Confirmed DELETE/NoSuchKey is success; NoSuchBucket, arbitrary 404,
binding mismatch, access denial and uncertainty are failures. Cancellation is
checked between operations. Receipt recording revalidates membership, allocation,
fresh lifecycle and work, then atomically commits monotonic byte proof, member
completion and the final V18 checkpoint CAS. A reused receipt also gets a new
member-completion checkpoint, never another receipt. Duplicate completion advances
nothing. A crash before receipt commit repeats the immutable DELETE.

[V20](../backend/src/main/resources/db/migration/V20__folder_file_deletion_progress.sql)
adds retained member progress and immutable never-written receipts to V17 work.
[V21](../backend/src/main/resources/db/migration/V21__guard_folder_file_proof_transitions.sql)
rejects phase shortcuts and proofs without verified allocation preclusion.
For verified allocations with write_started=false, completion locks the same
file/allocation used by beginWrite, permanently sets write_precluded_at, records
never_written_at/job ID and attaches completion in the checkpoint transaction.
No DELETE or artificial byte-deletion receipt is needed. Allocation constraints,
monotonic triggers and beginWrite's conditional update prohibit future admission.
An admitted UPLOADING/FAILED writer remains unsafe regardless of age; no lease
or FAILED timestamp establishes writer quiescence. Unknown legacy bindings are
never inferred from current settings, paths, keys or HEAD.

First observations of UNSAFE_WRITER, UNKNOWN_BINDING, IDENTITY_MISMATCH or
UNKNOWN_TARGET commit a blocked disposition and checkpoint. This useful work
allows later unexamined members to progress. Reobserving a blocked member does
not checkpoint; an invocation with no new work returns RETRY and spends the
ordinary failure budget. Garage errors return RETRY even if earlier members in
the page completed. Useful pages return delayed CONTINUE without spending the
failure budget; committed receipts survive failure, abandonment and replacement.

The final transition takes exclusive hierarchy coordination before fresh root,
run progress and the last job fence. All manifest members must have validated
completion plus byte-deletion or never-written proof. V20 guards reject premature
DELETE_FOLDERS, reversed phases, identity/seal changes and receipt replacement.
The phase and checkpoint commit atomically. Slice 5 itself removed no folder
metadata and stopped at DELETE_FOLDERS. Slice 6 below replaces that former RETRY
boundary with metadata execution; successful incomplete batches use CONTINUE.

Recovery: preserve root intent, run, manifest, allocations, shared work and jobs.
Resume the same event/job using ordinary retry/expired-claim recovery; receipts
skip I/O and blocked dispositions keep unsafe rows visible. Do not clear intent,
reset terminal jobs, recreate events or repair immutable snapshots automatically.
A terminal standalone failure does not block an eligible folder claim. Unknown
bindings, lifecycle corruption or admitted uncertain writes require independently
verified, approved repair/reconciliation, which remains outside this slice.
Restoring independently verified original storage-client settings can resolve a
binding mismatch; no historical-client router or writer-fencing protocol is added.

Verification on 2026-10-14: 220 final focused tests passed, including 46 new
FolderPurgeFileIntegrationTest cases. The final full safely runnable suite ran
620 tests: 613 passed, seven environment-gated RabbitMQ tests skipped, zero
failures/errors. Skips were three RabbitOutboxIntegrationTest and four
RabbitPurgeConsumerIntegrationTest cases; topology/v1 contracts were unchanged.
V19-to-V20/V21 migration, PostgreSQL phase/proof/receipt guards, multi-batch
continuations beyond ten claims, stale/expired/checkpoint rollback, write admission,
in-flight standalone overlap, crash-after-DELETE replay, missing objects, unsafe
uploads, legacy targets/bindings, drift/failures, runtime/cancellation and unrelated
Garage object preservation passed. Existing standalone finalization/recovery and
discovery regressions passed. All local documentation links and git diff --check
passed; no credentials/environment/generated files belong to the task diff.

New Garage mutations used only disposable UUID owner/file objects; no VM
infrastructure, credentials or existing Garage user data were changed. These tests
establish deterministic crash/transaction boundaries, not process-kill recovery,
throughput or load limits. Workers, consumers and publisher remain disabled by default.


## Slice 6 bottom-up metadata removal and durable finalization

[FolderPurgeMetadataService](../backend/src/main/java/com/vaultdrive/worker/FolderPurgeMetadataService.java)
executes DELETE_FOLDERS without Garage I/O. Each invocation performs exactly one
transactional page of 1-20 file finalizations or leaf-folder removals. Files are
selected by UUID, then folders by UUID among eligible leaves. A parent becomes
eligible only on a later invocation after its children's removal receipts commit.
The root has its own final invocation after every nonroot receipt exists.

[V22](../backend/src/main/resources/db/migration/V22__folder_metadata_finalization.sql)
adds retained `folder_purge_removals`, immutable sealed folder/file counts and
`finalized_at`. Existing sealed V19-V21 runs receive their current manifest counts;
DISCOVER runs capture counts at sealing. Stop old workers before rollout; old
binaries cannot execute FINALIZED. Applied V16-V21 migrations are untouched.
Count mismatches block missing manifest records, including records for already
removed folders. Counts complement immutable membership and fresh relational
checks; they are not protection against an operator rewriting database evidence.

The state machine is now `DISCOVER -> DELETE_FILES -> DELETE_FOLDERS -> FINALIZED`.
DELETE_FOLDERS first removes remaining file metadata using V17's shared atomic
removal/receipt primitive. It revalidates V19 lifecycle/ownership/binding identity,
V16 allocations and original standalone targets, V20 completed membership and
verified byte/never-written proof. Never-written finalization requires permanently
precluded original allocation; V22 permits this proof in the finalization constraint.
Missing file metadata requires an already matching metadata-finalization receipt;
byte proof alone cannot create one. A finalized file that reappears blocks removal.
Standalone finalizers and folder finalizers serialize through hierarchy/work locks,
reuse the same receipts and leave standalone job outcomes/counters unchanged.

Folder removal requires sealed complete enumeration, matching owner/parent/trash
snapshot, correct root intent or no descendant intent, finalized direct files,
child removal receipts and no actual file/child-folder rows. Unexpected descendants,
missing resources without receipts, mismatched snapshots, unrepresented ancestor
purges, cycles with no leaf, missing work/progress/allocations and corrupt manifests
fail closed. PostgreSQL's non-cascading ownership/parent/file foreign keys remain
the final guard. No root fallback, inferred target or automatic repair is added.

Root deletion, root removal receipt, FINALIZED timestamp/state and final V18
checkpoint commit atomically. The worker records job SUCCEEDED separately. A lost
completion replays FINALIZED only after validating retained counts, complete
membership, all folder receipts, exact verified file finalization proofs and absent
metadata, under a live fenced claim. A missing root with DELETE_FOLDERS or missing
run is never success. Replaying FINALIZED creates no checkpoint or replacement
receipt. Incomplete successful pages return CONTINUE, so healthy metadata work
can exceed ten claims without spending the failure budget.

Lock order is **exclusive owner hierarchy -> fresh root and selected resource rows
(in UUID order) -> allocation/work or run/removal progress -> job row for final
fencing**. Each page has a ten-second transaction limit, five-second statement
limit and two-second lock-wait limit. Context checks precede work and the final
checkpoint; cancellation or stale revision/token/PostgreSQL-clock lease rolls back
the entire page. The handler reserves ten seconds of remaining local runtime.
Set-based integrity/count queries can time out for very large manifests; they fail
closed rather than promise measured load capacity. No transaction spans Garage I/O.

Recovery preserves event/job/run, manifests, original allocations/targets, file
receipts and removal receipts. Ordinary retries/expired-claim recovery resume the
first remaining file page or current leaves. Crash before page commit rolls back
both removal and progress; crash after commit retains both and charges abandonment
once. Crash after child removal never rediscovers the deleted child. Crash after
root commit needs only validated FINALIZED replay and ordinary job completion.
Do not clear root intent, delete retained proof, reset terminal standalone jobs,
recreate events or rewrite snapshots. Missing/corrupt proof and unsafe writers need
independently verified approved repair; no automatic reconciliation/reset/retention
or scheduled historical finalization recovery is introduced. All enablement defaults,
v1 events, RabbitMQ topology, APIs and trusted-writer assumptions remain unchanged.

`FolderPurgeMetadataIntegrationTest` uses real PostgreSQL and disposable Garage
objects for nested and many-batch deletion, child/root crash boundaries, duplicate
and stale workers, lock-wait lease expiry, atomic checkpoint/cancellation rollback,
both concurrent standalone finalizer orders, failed standalone overlap, FK guards,
unexpected descendants, missing/corrupt records and durable-success replay. These
are deterministic transaction boundaries, not process-kill or load experiments.
Verification on 2026-10-15 (Asia/Calcutta): the expanded focused run passed 249
tests, followed by all 30 final metadata tests after adding V21-to-V22 retained-run
backfill coverage. The full safely runnable suite ran 650 tests: 643 passed, seven
environment-gated RabbitMQ tests skipped, zero failures/errors. Skips were three
RabbitOutboxIntegrationTest and four RabbitPurgeConsumerIntegrationTest cases;
v1/topology remained unchanged and live broker behavior was not reverified.
Real PostgreSQL migration/constraints, continuation beyond ten metadata claims,
both concurrent standalone winner orders, stale/lock-wait fencing, rollback and
disposable Garage regressions passed. The migration scratch schema and data roll
back transactionally. The failed initial spy setup was corrected; its one leftover
test fixture was cleaned with explicit approval. No VM infrastructure, credentials
or existing Garage user objects were changed. Compilation, 84 local documentation
links, task-source secret-pattern scanning and git diff --check passed. No
process-kill, production load or throughput claim follows from these tests.
