# Physical file purge (Phase 2D.1 and Phase 2D.2 Slices 2–6)

M5 extends this legacy purge design with [multipart compatibility](multipart-purge-compatibility.md):
READY requires multipart completion evidence; unfinished folder members use retained
logical-discard proof. Folder success does not promise physical reclamation of
those unfinished allocations.

## Accepted storage contract

VaultDrive retains Garage 2.3.0 under a **trusted-writer assumption**. All writers
with access to application objects must obey permanent key nonreuse, finish a
single upload before READY finalization, and never replace finalized content.
Operators must not repoint a storage namespace, endpoint or bucket alias to a
different cluster/bucket without an approved identity migration. This is an
operational contract, not a storage-side write-once guarantee.

Garage does not enforce destination create-only PUT/multipart completion, S3
bucket policies or S3 version-specific/conditional deletion. In particular, its
returned `x-amz-version-id` is not a usable deletion fence. See the [compatibility
documentation](https://garagehq.deuxfleurs.fr/documentation/reference-manual/s3-compatibility/)
and tagged 2.3.0 [PUT](https://raw.githubusercontent.com/deuxfleurs-org/garage/v2.3.0/src/api/s3/put.rs),
[multipart](https://raw.githubusercontent.com/deuxfleurs-org/garage/v2.3.0/src/api/s3/multipart.rs)
and [DELETE dispatch](https://raw.githubusercontent.com/deuxfleurs-org/garage/v2.3.0/src/api/s3/api_server.rs) sources.
An out-of-band overwrite at a target key can be deleted by a later purge attempt.
UUID uniqueness, ETags, database leases and HEAD-before-DELETE do not remove this
accepted risk. Keep storage credentials private and restrict independent writers.

Presigned/multipart transfers remain unimplemented. A future implementation must
retain durable allocation, control upload initiation/completion, revoke or exhaust
all writable capabilities before READY, and address late multipart writers before
reusing this handler. Do not issue reusable final-key PUT URLs under this contract.

## Storage identity and transaction boundaries

V16 adds two PostgreSQL tables, without cascading resource/outbox/job foreign keys:

- `file_storage_objects`: globally unique storage key, unique file ID, owner ID,
  namespace/endpoint/region/bucket binding and monotonic `write_started`. New
  allocations commit with UPLOADING metadata. Allocations survive file removal
  and must be retained permanently as key tombstones.
- `file_purge_targets`: immutable event-ID target, file/owner IDs, expected file
  version and purge timestamp, and the exact namespace/endpoint/region/bucket/key.
  The target commits with pending purge and the unchanged v1 outbox event.

`S3_NAMESPACE` / `storage.s3.namespace` defaults to `garage-default`; it is a stable
logical cluster identity, not a cluster identifier verified by Garage. Choose a
distinct value for a different cluster, including one reachable through the same
URL. New uploads capture the binding at reservation time. Purge copies that binding
instead of resolving a location using current configuration.

Database triggers prevent allocation identity changes, resetting `write_started`,
and target updates. No application deletion/retention path removes these records.
New metadata reservation retains the existing shared hierarchy lock; allocation
insertion follows the file flush. Before PUT, a short transaction takes the shared
owner hierarchy lock, freshly locks the eligible UPLOADING file row, validates
destination accessibility, and consumes the allocation row once. It commits before
Garage I/O. A second invocation, including one after an uncertain storage failure,
cannot write the same key. SDK automatic retries are disabled: retry uploads through
a new file/key. A timed-out PUT may still finish at Garage, but cannot become READY
through the failed API request and is outside this handler's eligibility.

Legacy file keys are seeded as consumed allocations with **unknown bindings**.
V16 does not guess their original namespace, endpoint or bucket. Existing uploads
in flight at migration time cannot begin a new write invocation; coordinate rollout
with uploads. Existing API responses and v1 event/consumer/job identity contracts
remain unchanged. Legacy purge requests can still be accepted but have no executable
target. Requests predating the outbox also remain unbackfilled.

## Execution and outcomes

The production `FilePurgeJobHandler` supports `FILE_PURGE_REQUESTED` notifications
through the existing `FILE_PURGE` job type. Slice 5 also registers
`FolderPurgeJobHandler` for sealed folder membership.

Preparation reads the immutable event-ID target, takes the shared owner hierarchy
lock, then freshly locks the file row. Ownership, key, version and purge timestamp
must match a pending READY file and its retained verified allocation before new
work is created. V17 captures that storage identity and lifecycle snapshot in
`file_purge_work`, unique by file ID. Work rows have no resource/job foreign keys;
they survive metadata removal. SQL constraints and an update trigger preserve
immutable identity and receipts. V16 targets and allocations are unchanged.

Resource transactions use **owner hierarchy lock → fresh resource rows → work
rows → job row for final fencing**. Scalar SQL reads avoid stale managed-entity
state. Token, job identity and PostgreSQL-clock lease checks after the job lock
and at the final write reject stale progress by rolling back the transaction.
Existing job-only claim/renew/complete/retry operations retain their lock protocol.
Preparation commits before a live-token lease renewal and Garage DELETE.

The storage adapter requires the persisted binding to exactly match its trusted
configured client. A changed namespace, endpoint, region or bucket fails closed.
It sends only the persisted bucket/key, never constructs clients from durable
payloads/URLs, and never infers a target from metadata or a missing object.
Only the matching configured client is supported in this slice; no automatic
routing to historical locations is implemented.

DELETE has a ten-second SDK call deadline, five-second attempt deadline, two-second
connection/pool-acquisition limits and five-second socket timeout. Transport limits
also apply to uploads/downloads; they are idle/network limits, not a total streamed
transfer deadline. SDK retries are disabled; durable jobs govern retries.
Context checks before/after preparation and I/O honor cancellation/interruption;
the existing monitor continues renewal and cancellation. Database ownership is
checked immediately before I/O, but expiry/JVM pauses can still overlap external
effects. A stale in-flight DELETE may finish and replay the same target.

Successful DELETE and provider `404 NoSuchKey` mean success. `NoSuchBucket`, arbitrary
404, access denial and configuration mismatch never mean object absence. Network
uncertainty, 429 and 5xx are classified transient. Other failures have sanitized
access/binding/storage classifications. All failed outcomes use the existing
bounded ten-attempt/backoff lifecycle, including failures requiring operator repair;
there is no new immediate-failure state. No raw exception detail is logged.

After verified DELETE/absence, a short fenced transaction records
`bytes_deleted_at` and `byte_receipt_job_id`. A separate fenced transaction
conditionally deletes metadata and records `metadata_finalized_at` atomically.
Receipts can only be established once, never cleared or replaced. Finalization
requires matching owner, folder, key, version, READY status, creation/update/trash/
purge timestamps, and a still-matching V16 target/allocation. Any mismatch retains
metadata; byte success remains independently meaningful.

An original standalone file intent can delete bytes beneath pending folder
ancestry, but metadata removal is deferred beneath each pending folder ancestor until
its Slice 4 run is sealed and exact file membership matches the standalone work.
The shared hierarchy lock makes this check atomic with exclusive folder purge
requests. Unknown/unsealed runs or missing/mismatched membership still defer removal.
Retained file rows preserve enumeration; sealed manifests survive standalone removal.
Slice 5 adds folder byte execution and coordinated never-written proofs.
Admitted UPLOADING/FAILED writers and unknown legacy bindings remain blocked.

Explicit handler success permits the worker's live-token/unexpired-lease fenced
completion. A cancelled or stale result cannot record receipts or complete a newer
claim. No PostgreSQL transaction or hierarchy lock spans Garage I/O.

**SUCCEEDED means the intended object's bytes were deleted or already absent.**
It does not imply metadata finalization. Allocation, target, work, receipts and
job identity remain after file metadata removal. Slice 6 implements bottom-up folder removal.
Reconciliation, historical-intent backfill, retention and reset tooling remain
unimplemented. The HTTP permanent-delete API still only records intent.

## Crash recovery and historical success

- DELETE succeeds but no receipt commits: retry DELETE against the same immutable
  target. Missing metadata without a byte receipt blocks execution; it is never
  proof that Garage deletion succeeded.
- Byte receipt commits but finalization fails/rolls back: retry uses the receipt
  without another DELETE. Metadata and its finalization receipt commit together.
- Metadata removal commits but job completion is lost: replay uses retained work
  and receipts, then the worker fences completion normally.
- Metadata is independently missing with only a byte receipt: byte success can
  complete, but no metadata-finalization receipt is invented. Retain for review.

`FilePurgeRecoveryService.recoverPage(afterJobId, limit)` is an explicit Spring
service operation, not a scheduled worker or HTTP endpoint. Invoke without an
enclosing transaction; `limit` is 1–100. Start with a null cursor, pass each page's
`nextCursor` to the next call, and stop on `examined == 0`. Restart from null in a
later recovery pass to reconsider deferred rows. UUID keyset pagination advances
past blocked rows; each candidate has its own short transaction. Reports count
examined/finalized/blocked candidates, without storing raw exception details.

The scan selects only matching V16 targets for `SUCCEEDED FILE_PURGE` jobs with
no finalization receipt. It verifies target/allocation and fresh metadata before
creating missing work. Under the final job lock it imports authoritative historical
byte success as a receipt, then attempts the same conservative finalization.
The import timestamp is receipt-adoption time, not an inferred Garage deletion
time. Job status, attempts, completion time and updated time are unchanged; no
Garage call, terminal-job reset, inferred target or success reinterpretation occurs.
Missing/changed metadata without existing work stays blocked. Existing matching
work can retain byte proof while finalization is deferred. FAILED/PENDING/RUNNING
jobs and pre-V16/unknown targets are excluded. Database failures propagate;
identity/eligibility failures remain blocked and the cursor advances.

## Enablement and recovery

Workers, consumers and publisher remain independently disabled by default.
Registering this real handler means explicitly enabling the worker can now execute
eligible file purge jobs. Repository verification is not production enablement.

For a failed/old request, inspect its event-ID target, authoritative job state and
allocation first. Do not recreate a target from today's S3 settings or HEAD output.
Unknown legacy bindings require an independently audited, operator-approved repair
or migration; this slice provides no such backfill. For a binding mismatch, restore
the independently verified original configuration or design an approved migration.
For access/network errors, repair credentials/connectivity without changing target
identity, then approve any terminal-job retry/reset separately. Do not automatically
reset attempts, clear intent, restore metadata, or rewrite v1 events.

Retain job IDs for deduplication and targets/allocations for recovery and nonreuse.
Never recreate a purged key after bytes are removed: an old external DELETE can still
arrive. Storage alias/DNS reassignment and unauthorized writers remain operator risks.

## Verification

`FilePurgeIntegrationTest` uses real PostgreSQL and Garage with a fresh fixture owner
and UUID file keys per test. It covers deletion, missing objects, successful-effect
replay, transient failures/retry, stale/cancelled claims, late external effects,
actual worker completion, transaction/lock release, immutable target/allocation
guards, atomic rollback and legacy migration, and preservation of newer/unrelated
objects. Slice 2 additionally covers independent receipt/finalization crash and
rollback boundaries, concurrent duplicates, stale/lock-wait expiry fencing,
missing metadata, lifecycle/ownership/key mismatch, permanent allocation retention,
historical success and bounded recovery, and both folder-purge/finalization winner
orders. `S3ObjectStorageServiceTest` checks binding rejection, sanitized provider
classification, no enclosing transaction and timeout configuration. Existing storage
tests now allocate unique keys too. Tests delete only their own disposable objects;
they do not modify buckets or VM services.

```powershell
cd backend
. .\.env.local.ps1
.\mvnw.cmd "-Dtest=FilePurgeIntegrationTest,S3ObjectStorageServiceTest,S3StorageIntegrationTest,PurgeOutboxIntegrationTest,FolderPurgeConcurrencyIntegrationTest" test
# For the safely runnable full suite, keep live broker tests environment-gated:
Remove-Item Env:RABBITMQ_TEST_VHOST -ErrorAction SilentlyContinue
.\mvnw.cmd test
```

Live Garage test mutation requires explicit authorization. Results and any excluded
broker checks belong in task reports. No production load or real process-kill proof
is claimed.

## Slice 4 discovery participation and recovery

V19 retains folder run identity/frontiers and file membership separately from V17
work. Discovery creates no inherited file-purge work and performs no Garage I/O.
Standalone byte receipts remain valid during traversal. Finalization acquires the
shared hierarchy lock, then requires a sealed run for every pending ancestor,
matching root request timestamp and exact manifest event/owner/folder/key/version/
READY/lifecycle/binding snapshot. Missing or corrupt participation fails closed.
Historical-success recovery uses this same finalizer without resetting a job.
A finalizer that wins before root acceptance is outside accepted membership;
acceptance that wins preserves the row until discovery has sealed. This check
and metadata removal occur under the same hierarchy coordination.

Resume folder discovery from its committed cursors, not by recreating events or
resetting jobs. Slice 5 resumes sealed DELETE_FILES using shared work; Slice 6 completes bottom-up
folder removal. See [the discovery recovery
contract](folder-purge-design.md#slice-4-durable-paginated-discovery).

## Slice 5 shared folder work and blocked-file recovery

The registered folder handler uses sealed V19 membership and the original V16
allocation to prepare exact immutable V17 work. It validates standalone V16
targets and reuses existing monotonic receipts; it never resets or waits for the
standalone job. Folder receipt ownership can therefore name a FOLDER_PURGE job.
Missing metadata with matching retained proof can participate; missing metadata
without proof blocks. Existing metadata must match the captured lifecycle exactly.

V20 adds a separate never-written proof for verified, unconsumed allocations.
Under shared hierarchy/file/allocation locks, it permanently precludes beginWrite
and commits proof/member completion with a live-token checkpoint. This is not a
Garage deletion receipt. Admitted UPLOADING/FAILED allocations remain blocked,
including old failures and uncertain PUT outcomes. Unknown bindings and legacy
standalone intents missing original targets remain blocked. Folder work does not
perform inherited-file metadata finalization in Slice 5.

DELETE/receipt crashes replay the retained target. Existing proof skips DELETE.
Blocked members retain controlled reasons; initial disposition is checkpointed,
unchanged blocked retries consume ordinary failure budget, and fresh members are
processed first. Keep the root barrier and inspect immutable membership/work/
allocation identity during recovery. No automatic reconciliation, snapshot rewrite,
terminal-job reset or unsafe-writer quiescence inference is provided. Folder byte
completion transitions to DELETE_FOLDERS; Slice 6 now executes metadata removal.
See [Slice 5 workflow and recovery](folder-purge-design.md#slice-5-folder-led-file-byte-deletion).


## Slice 6 shared finalization and folder recovery

Folder DELETE_FOLDERS reuses V17's atomic file metadata removal/receipt primitive,
with exact sealed member lifecycle/binding/ownership and original allocation/target
checks. V22 also permits finalization after V20's coordinated never-written proof,
without inventing a byte receipt. Missing metadata with only byte proof cannot
acquire a finalization receipt. Matching already-finalized work skips removal.

Folder and standalone finalizers serialize under hierarchy/resource/work locks and
reuse immutable proof; no standalone job status, counters or receipts are reset.
A standalone job can finish after the folder metadata/root is gone from retained
V16/V17 identity and byte proof. Folder success additionally requires durable
FINALIZED run proof after root-last removal. Retain all evidence for crash replay;
missing/corrupt records require approved review rather than inferred success.
See [Slice 6 recovery](folder-purge-design.md#slice-6-bottom-up-metadata-removal-and-durable-finalization).
