# Physical file purge (Phase 2D.1)

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
through the existing `FILE_PURGE` job type. There is no folder handler.

Preparation reads the immutable event-ID target, takes the shared owner hierarchy
lock, then freshly locks the matching pending READY file row. Ownership, key,
version and purge timestamp must match; pending-purge ancestry is checked. If a
folder purge subsequently owns that ancestry, the file job waits/fails safely and
needs operator review. Job locks are not held with hierarchy/resource locks.
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

Explicit handler success permits the worker's live-token/unexpired-lease fenced
completion. A DELETE followed by crash or lost completion is safely replayed under
the trusted-writer contract. A cancelled or stale result cannot complete a newer
claim. No PostgreSQL transaction or hierarchy lock spans Garage I/O.

**SUCCEEDED means the intended object's bytes were deleted or already absent.**
Pending file metadata, allocation and target remain. Final metadata removal,
recursive folder purge, reconciliation, backfill, retention and reset tooling are
not implemented. The HTTP permanent-delete API still only records intent.

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
objects. `S3ObjectStorageServiceTest` checks binding rejection, sanitized provider
classification, no enclosing transaction and timeout configuration. Existing storage
tests now allocate unique keys too. Tests delete only their own disposable objects;
they do not modify buckets or VM services.

```powershell
cd backend
. .\.env.local.ps1
.\mvnw.cmd "-Dtest=FilePurgeIntegrationTest,S3ObjectStorageServiceTest,S3StorageIntegrationTest,PurgeOutboxIntegrationTest" test
.\mvnw.cmd test
```

Live Garage test mutation requires explicit authorization. Results and any excluded
broker checks belong in task reports. No production load or real process-kill proof
is claimed.
