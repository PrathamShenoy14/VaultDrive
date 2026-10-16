# ADR-0007: Transaction-scoped hierarchy coordination

- Status: Accepted; folder operations and current file metadata mutation paths implemented
- Date: 2026-10-09

## Context

Create, rename, move, and restore make a check-then-write decision inside an owner/folder namespace. Unique indexes prevent invalid commits, but concurrent requests also need predictable state transitions and understandable errors. Restore and permanent-delete additionally compete for the same eligible Trash state.

## Decision

Introduce an owner-scoped hierarchy coordinator backed by PostgreSQL transaction advisory locks. It exposes shared and exclusive modes, requires an active transaction, and executes on the connection enlisted in the current JPA transaction. PostgreSQL therefore releases the lock on commit or rollback.

The signed `bigint` key has a stable derivation: fixed namespace `0x5641554C54445256` (ASCII `VAULTDRV`) XOR the UUID most-significant 64 bits XOR the UUID least-significant 64 bits rotated left by one. Reducing 128 bits to 64 can collide; a collision only causes conservative extra serialization. The formula must not change without a coordinated deployment because mixed versions must contend on the same key.

Destination namespace locks use PostgreSQL's separate two-`integer` advisory-lock key space: fixed class `0x56444E53` plus a stable 32-bit mix of owner and destination UUIDs, with `null` representing root. Hash collisions only add conservative serialization.

Folder create, rename, move, trash, restore, and permanent-delete request acquire the exclusive hierarchy lock before any protected read. A folder purge request additionally rejects pending-purge ancestors and descendants, so overlapping parent/child intents serialize to one winner. These paths remain PostgreSQL-only operations, so the advisory lock is never held across Garage I/O.

Folder restore and permanent-delete request retain the order: exclusive owner hierarchy lock, then fresh owned Trash-row pessimistic lock. Both reject the row's own pending purge before mutation. For concurrent requests on the same folder, the first committed transition wins and the other returns `404 Not Found`; restore retains ancestor-purge rejection before root fallback. PostgreSQL tests cover both winner orders and direct pending-purge service rejection. V11 remains the final constraint guard; no locking or purge execution architecture changes are introduced by Phase 2D.2 Slice 1.

File rename, move, trash, upload reservation, and upload finalization acquire the shared hierarchy lock before loading current file or ancestor state. Each performs fresh validation and writes inside a short transaction. Garage transfer and cleanup remain outside database transactions and hierarchy locks. Because shared locks allow file mutations for the same owner to overlap, the file row has an optimistic version; upload finalization additionally uses an expected-status/version conditional transition so only one `UPLOADING -> READY|FAILED` change can win.

File restore and permanent-delete request acquire the shared hierarchy lock before the fresh eligible Trash-row read. Both pessimistically lock that row, so restore and purge request cannot both transition it. Restore validates the original ancestry while holding the hierarchy lock, falls back to root when inaccessible, and then takes a destination-scoped exclusive advisory lock before extension-preserving collision naming. Lock order is owner hierarchy, file row, then destination namespace. Future purge/reconciliation workers remain outside this coordination domain.

## Consequences

- All folder mutations for one owner still serialize; different owners normally proceed independently.
- Shared mode coordinates file rename, move, and trash with folder structural operations while allowing unrelated file metadata mutations to overlap.
- A rare derived-key collision can serialize different owners without weakening correctness.
- Restore collision allocation is serialized only within one destination; unrelated destinations can proceed concurrently.
- Upload reservation and finalization now coordinate with folder structural operations without holding the advisory lock during Garage I/O.
- Database constraints remain mandatory after any lock refactor.

## Verification and remaining migration

- PostgreSQL integration tests cover hierarchy and destination namespace compatibility/blocking, rollback release, and the transaction requirement.
- The existing concurrent folder-restore integration test exercises the migrated folder path.
- PostgreSQL-backed races cover file move versus folder trash, rename versus folder trash, same-file optimistic conflicts, upload reservation versus folder trash, trash during the transfer gap, competing finalization transitions, both file restore/permanent-delete winner orders, restore versus folder trash/move, same-name restores, folder purge versus child/file restore, folder move, upload reservation, and both parent/child purge winner orders.
- Add future purge/reconciliation race coverage when those workers are implemented.
- A reproducible baseline and post-change contention measurement.

## Phase 2D.1 storage admission and file purge

New upload allocation insertion follows the file flush under the existing shared
owner hierarchy lock. Storage write admission reads immutable allocation identity
to identify the owner, then takes the shared owner hierarchy lock, freshly queries
and pessimistically locks the eligible UPLOADING file row, and consumes the
allocation row. The order is hierarchy, file, allocation; commit precedes PUT.
The durable one-write marker and retained key constraints prevent another
application invocation from writing the same key. No lock spans storage I/O.

File purge preparation similarly reads only immutable event-ID target identity
before taking the shared owner hierarchy lock, then freshly locks the matching
owned pending READY file row and validates key/version/purge timestamp and
pending-purge ancestry. It does not mutate the namespace or finalize metadata.
The resource transaction commits before live job-token renewal and DELETE; job
row locks never overlap hierarchy/file locks. Existing worker completion is fenced
in its own short transaction. A stale external effect is still possible under the
accepted [trusted-writer contract](../file-purge.md).

Real PostgreSQL/Garage tests cover competing writes, row-lock-before-admission,
resource/job lock release before storage I/O, stale claim rejection and late
effects without newer-object deletion. Existing hierarchy/request races remain
in the full verification suite. Metadata finalization/folder traversal will need
their own locking decisions and race coverage.

No performance benchmark currently exists, so this ADR makes no throughput claim.

## Phase 2D.2 Slice 2 progress and finalization

Resource progress transactions extend the order to **shared owner hierarchy lock,
fresh scalar file-row lock/read, work-row lock, job-row lock for final fencing**.
Only immutable V16 identity is read before hierarchy coordination. Scalar queries
avoid stale JPA first-level resource state. Job token/type/aggregate and
PostgreSQL-clock lease checks after the final job lock and at the final write roll
back stale work, byte receipts and metadata removal. Existing job-only operations
retain their protocol; no path takes hierarchy/resource locks while holding a job
lock first. Garage I/O occurs only after preparation commits and before receipt
recording begins.

Standalone metadata finalization requires an exact captured owner/folder/key/
version/READY/lifecycle match and a durable byte receipt. Every pending folder
ancestor defers removal until a future manifest can prove sealed discovery. The
shared hierarchy lock coordinates this check/removal with exclusive folder purge
requests in both winner orders. Byte deletion from the original standalone intent
may continue beneath that barrier. Historical-success recovery uses the same
order and finalizer, with locked authoritative SUCCEEDED status replacing a live
claim; it never rearms that job. PostgreSQL/Garage tests cover both folder winner
orders, duplicate finalizers, cached stale rows, rollback and lease expiry during
job-lock waits. See [file purge](../file-purge.md).

## Phase 2D.2 Slice 3 continuation checkpoints

The generic checkpoint follows the same hierarchy → freshly protected resource →
work/progress → job order. `BackgroundJobService.checkpoint` requires the caller's
existing transaction, flushes work, takes the job lock last, then applies an
expected-version CAS with the exact claim token and PostgreSQL-clock live lease.
A failed CAS/fence throws and rolls back work; no hierarchy/resource lock may be
acquired afterward. The revision must accompany a newly useful durable business
unit, never a heartbeat or replay. Each future handler must verify that semantic
and atomicity contract for its own work schema.

CONTINUE itself locks only the job row in a separate transaction after the work
commit. It reads protected accounting/checkpoints under that lock and rechecks
the token, lease and progress predicate at the transition. Cancellation may leave
committed progress with an abandoned claim; recovery preserves it and charges one
failure. No lock spans external I/O or handler dispatch. New PostgreSQL tests
cover continuation lock-wait expiry, stale checkpoints and atomic fixture rollback.
These passed against real PostgreSQL; executed totals and verification limits are
recorded in [the Slice 3 record](../folder-purge-design.md#slice-3-continuation-foundation).

## Phase 2D.2 Slice 4 discovery and sealed participation

Folder request acceptance additionally persists a small retained run with intent
and outbox. Request overlap checks and discovery transactions have ten-second
transaction, five-second statement and two-second lock-wait limits; timeout rolls
back acceptance or the current page. The stable advisory key is unchanged.

Discovery acquires **exclusive owner hierarchy -> fresh root/folder reads ->
run/frontier progress -> job checkpoint lock/fence**. It enumerates one UUID page
at a time and never acquires hierarchy/resources after the job lock. Membership,
cursors, scan completion and seal commit with the checkpoint CAS. The checkpoint
also verifies authoritative job type/aggregate identity. Current resource reads
are scalar queries rather than cached entities. No external I/O occurs.

Standalone finalization retains shared hierarchy -> fresh file -> work -> job.
A pending ancestor permits removal only with matching sealed run and exact
standalone file membership; unsealed/legacy/mismatched state defers. Exclusive
page/seal coordination serializes this check against discovery. Accepted root
intent preserves all existing move/restore/upload/nested-purge barriers. Tests
cover bounded restart, barriers, both acceptance/finalization winner orders,
discovery/finalization overlap, cancellation and job-lock-wait expiry rollback.
Corrupt recorded parent edges and cycles fail closed; there is no recursive Java
walk or performance claim. Folder byte execution/removal remains future work.

## Phase 2D.2 Slice 5 file execution locks

Folder per-file preparation/receipt/proof uses **shared owner hierarchy -> fresh
root/file/allocation -> shared V17 work/member progress -> folder job fence**.
Original run identity is the only pre-lock read. File and allocation rows are
locked before validation; no resource lock is acquired after the final job lock.
The allocation lock coordinates permanent never-written preclusion with beginWrite.
The proof, member completion and checkpoint CAS commit atomically; stale token,
lease or revision rolls all of them back. Immutable allocation constraints prevent
an admitted writer from being reclassified as never-written. Admitted uncertain
writers remain blocked; no storage-side fencing is invented.

Phase transition uses exclusive hierarchy -> fresh root -> run progress -> final
job checkpoint. Standalone workers keep their original hierarchy/file/work/job
order, with sealed participation also recognized at DELETE_FOLDERS. The folder
path never locks a standalone job or requires its success. Preparation commits
before renewal/DELETE; Garage I/O never spans a PostgreSQL transaction. Both jobs
can repeat the same immutable DELETE and establish one monotonic shared receipt.
See [Slice 5 tests and recovery](../folder-purge-design.md#slice-5-folder-led-file-byte-deletion).


## Phase 2D.2 Slice 6 durable metadata removal

Extend the sealed run with retained V22 folder-removal receipts, immutable manifest
counts and FINALIZED timestamp. Reuse shared V17 file removal/receipt transactions
for verified byte proof or V20 coordinated never-written proof, preserving exact
owner/lifecycle/storage/manifest/target validation and pending-purge barriers.
Standalone job state/counters/receipts are never reset or replaced.

Use exclusive owner hierarchy coordination for each metadata page: fresh root and
resource rows in UUID order, allocation/work or run/removal progress, then job row
for the final checkpoint CAS/token/PostgreSQL-clock lease fence. No resource lock
is acquired after the job lock and no transaction spans Garage I/O. At most twenty
files or current leaf folders are removed per ten-second transaction; statements
and lock waits retain five-/two-second bounds. Set-based integrity scans can time
out safely on large manifests; no performance measurement is claimed.

Complete enumeration, finalized direct files, matching folder snapshots, child
receipts and absence of actual descendants precede removal, with existing
non-cascading FKs as the final guard. Root removal, root receipt, FINALIZED state
and checkpoint commit together; job completion is separate. Missing root succeeds
only from revalidated retained FINALIZED proof. Incomplete useful batches CONTINUE,
while stale/expired/replayed checkpoints roll back mutations. Crashes resume
committed leaves or FINALIZED replay. Counts and retained proof fail closed on
missing manifests/metadata; no automatic repair, reset, retention or inferred
writer quiescence is added. APIs, disabled defaults, v1 events and topology remain.
See [Slice 6 tests and recovery](../folder-purge-design.md#slice-6-bottom-up-metadata-removal-and-durable-finalization).


## Phase 2E.2 HTTP transaction joining

The confirmed failure was a Spring EntityManager wrapper reporting mismatch, not
a missing service transaction. Reproduced with Spring Boot 4.1.1, Spring ORM
7.0.9 and Hibernate 7.4.5.Final, using signed JWTs and an embedded HTTP server,
real services, committed fixtures in an isolated PostgreSQL test schema, and no
enclosing test transaction.

The request path is security filters -> DispatcherServlet -> TrashController ->
FolderService CGLIB proxy -> TransactionInterceptor -> JpaTransactionManager ->
FolderService.restoreFolder -> PostgresHierarchyCoordinator. File mutations use
the corresponding FileService/FileMetadataService proxies. There is no
self-invocation or missing transaction advice on the failing folder path.

With OSIV enabled, OpenEntityManagerInViewInterceptor creates and binds an
application-managed EntityManager wrapper through the managed factory.
JpaTransactionManager reuses that factory-bound holder and begins its underlying
resource-local transaction. The injected shared EntityManager resolves to this
same holder. However, ExtendedEntityManagerCreator's invocation handler answers
non-JTA isJoinedToTransaction() by checking a resource keyed by the native
EntityManager, rather than the factory-bound holder used by JpaTransactionManager.
That wrapper reports false even though its Hibernate delegate reports joined and
its native EntityTransaction is active. These branches were traced in the
installed Spring ORM bytecode and checked against the request-time observations.
The coordinator correctly fails closed on the wrapper's false result.

| Request configuration | Spring transaction active | Injected/bound EM joined | Native Hibernate joined / transaction active | Restore HTTP |
| --- | --- | --- | --- | --- |
| OSIV true (previous implicit default) | true | false | true / true | 500 |
| OSIV false | true | true | true / true | 200 |

Both paths resolve the same native EntityManager as their factory-bound holder;
OSIV true has the extended wrapper, while false has a native SessionImpl holder.
Diagnostics record only booleans, never bearer tokens, credentials, SQL parameter
values or resource contents. The retained OSIV-enabled diagnostic also reproduces
500 on folder create/purge and file restore/rename, with unchanged database rows.

Set spring.jpa.open-in-view=false explicitly in application.properties. This API
returns DTOs and obtains download streams in its service path; it needs no view
rendering persistence context. Without OSIV, JpaTransactionManager creates a
native transaction-owned EntityManager via createNativeEntityManager. The
coordinator's active-transaction AND joined-EntityManager guards remain unchanged.
No propagation, connection acquisition, transaction atomicity, authorization,
hierarchy key, lock order, or schema changes are introduced. No REQUIRES_NEW
workaround or manual join is added. Explicitly overriding OSIV back to true remains
unsupported on this dependency set; the diagnostic test intentionally preserves
that failing control rather than claiming both configurations work.

Real HTTP regression coverage uses the production default and separately checks
explicit false. Folder restore returns 200, purge requests return 202 with one
atomic event/run, and own-pending, active, missing, unauthorized, repeated and
pending-ancestor requests retain 404 (unauthenticated requests retain 401).
Rejected requests preserve full resource/intent/allocation rows, including
versions and timestamps. Injected failure after real outbox flush rolls back
both file and folder purge transitions and their retained intent. Folder/file
mutations, DTO reads and actual Garage upload/download exercise the same boundary;
reservation, write admission and finalization use joined short transactions
with storage I/O outside them.

Latch-controlled HTTP restore versus folder purge covers both commit winner
orders; PostgreSQL pg_locks confirms the actual competing request waits for the
owner advisory lock before re-reading eligibility. HTTP file move versus folder
trash covers shared/exclusive blocking and both winner orders, including the
unchanged file version on rejection after the folder commits first. Existing
PostgreSQL coordinator, lifecycle and outbox race tests retain their wider
coverage. See [architecture test evidence](../architecture.md#test-evidence) and
[the learning note](../learnings.md#an-active-transaction-and-a-wrapper-joined-check-can-disagree).

Publisher, consumer and worker processing stay disabled. No development database,
VM configuration, broker production topology or Garage configuration is changed.
This establishes the tested dependency/configuration behavior; dependency upgrades
must recheck the wrapper control. No load or performance claim is made.

## Phase 2E.4 missing-job ingestion

The reconciliation scan reads bounded committed scalar outbox identities without
resource locks. Ingestion rechecks age/quarantine and parses immutable source
identity before taking shared owner hierarchy -> fresh file/folder FOR SHARE ->
job identity insertion. Resource intent/target/run validation occurs after the
hierarchy and resource locks. There is no namespace/resource/proof mutation and
no resource lock after job insertion. Consumer ingestion remains job-only, so
there is no inverse resource/job lock order. Existing worker lifecycle, checkpoint,
lease and storage fences are unchanged; no lock spans broker or Garage I/O.

Each transaction retains five-second statement and two-second lock-wait bounds.
Real PostgreSQL tests force both consumer/reconciler insert winner orders and
assert one unchanged-identity job. Exclusive-hierarchy contention and scan table
locks time out without advancing business state; statement timeout rolls back
ingestion. See [reconciliation recovery](../missing-job-reconciliation.md).
