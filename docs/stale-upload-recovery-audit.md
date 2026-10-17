# Stale-upload and uncertain-write recovery design audit

Phase: **2E.5B — audit and design only**

Implementation follow-up: **B1 inventory and B2 never-admitted UPLOADING expiry
are now implemented separately**. See [inventory](stale-upload-inventory.md) and
[expiry eligibility, evidence, invocation and verification](stale-upload-expiry.md).
The original audit below remains the historical design record. B2 accepts the
one-hour configurable policy, explicit bounded pages and V24 retained allocation
expiry timestamp/resulting-version evidence. It conservatively requires active
ancestry and defers pending purge. B3 PUT-success receipts, admitted-write recovery
and B4 Garage cleanup remain proposals; no autonomous expiry is introduced.

Audit date: 2026-10-17

Audited code baseline: `7f37cdd`

## Conclusion and scope

The safe recovery boundary is **write admission, not upload age**. Never-admitted
reservations can be permanently closed using existing database coordination.
Admitted uploads with uncertain outcomes cannot be safely cleaned up merely
because they are old, `FAILED`, or absent according to HEAD.

The smallest useful implementation should recover never-admitted reservations
first, then add durable PUT-success evidence for future uploads. Uncertain admitted
writes should remain blocked.

The original audit recorded a read-only code/design review. No recovery code, tests,
migrations, database changes, infrastructure changes, object operations, or
background enablement were performed in that review. Saving the original audit
was a documentation-only follow-up; implementation described below was proposed
at that audited baseline.

Presigned URLs, S3 multipart support, broad orphan cleanup, and storage redesign
are outside this phase. The existing HTTP multipart form request is distinct from
S3 multipart upload.

## Sources and evidence boundaries

Reviewed AGENTS.md, the vaultdrive-concurrency and vaultdrive-async skills,
[architecture](architecture.md), [file purge](file-purge.md),
[process recovery](async-process-recovery.md), [learnings](learnings.md), the
development workflow/roadmap, relevant ADRs, upload/storage/file metadata code,
background job and purge implementations, relevant migrations, configuration,
and existing tests.

Primary implementation references:

- [FileController](../backend/src/main/java/com/vaultdrive/file/FileController.java),
  [FileService](../backend/src/main/java/com/vaultdrive/file/FileService.java), and
  [FileMetadataService](../backend/src/main/java/com/vaultdrive/file/FileMetadataService.java).
- [StoredFile](../backend/src/main/java/com/vaultdrive/file/StoredFile.java) and
  [StoredFileRepository](../backend/src/main/java/com/vaultdrive/file/StoredFileRepository.java).
- [FileStorageIdentityService](../backend/src/main/java/com/vaultdrive/storage/FileStorageIdentityService.java),
  [S3ObjectStorageService](../backend/src/main/java/com/vaultdrive/storage/S3ObjectStorageService.java),
  [ObjectStorageService](../backend/src/main/java/com/vaultdrive/storage/ObjectStorageService.java),
  [StorageBinding](../backend/src/main/java/com/vaultdrive/storage/StorageBinding.java), and
  [S3StorageConfig](../backend/src/main/java/com/vaultdrive/storage/config/S3StorageConfig.java).
- [FolderPurgeFileService](../backend/src/main/java/com/vaultdrive/worker/FolderPurgeFileService.java),
  [FilePurgeProgressService](../backend/src/main/java/com/vaultdrive/worker/FilePurgeProgressService.java),
  [BackgroundJobService](../backend/src/main/java/com/vaultdrive/job/BackgroundJobService.java),
  [JobWorker](../backend/src/main/java/com/vaultdrive/worker/JobWorker.java), and
  [MissingJobReconciler](../backend/src/main/java/com/vaultdrive/consumer/MissingJobReconciler.java).

The existing [17-scenario verification record](async-process-recovery.md#executed-real-process-loss-verification-2026-10-17-ist--2026-10-16-utc)
establishes purge-process recovery, not uncertain PUT recovery. Its documented
backend baseline is 720 tests: 719 passed, zero failures/errors, and one intentional
process-harness skip. Those tests were not rerun for this audit because their
fixtures mutate databases and storage. Code/test inspection is not a new execution
result. No live deployment state or infrastructure health was inferred.

Some historical skill/ADR status text predates later purge slices. Current code
and the later implementation records determine this audit's confirmed boundary.

## Confirmed current upload lifecycle

1. An authenticated HTTP multipart request supplies the file and optional
   destination. Ownership comes from the JWT subject.
2. The service validates the filename, generates a new file UUID and opaque key
   of the form `users/{ownerId}/files/{fileId}`, and constructs `UPLOADING` metadata.
3. Reservation takes the shared owner hierarchy lock, validates the owner and
   destination ancestry, checks name availability, flushes metadata, and inserts
   its storage allocation in the same transaction. PostgreSQL uniqueness remains
   authoritative.
4. Before PUT, `beginWrite` starts another short transaction. Its order is
   **shared hierarchy -> fresh eligible file-row lock -> allocation update**.
   It revalidates destination accessibility and changes `write_started` from false
   to true only once, provided writes have not been precluded and the persisted
   binding matches configuration.
5. Admission commits before Garage I/O. The synchronous SDK PUT uses the admitted
   bucket/key. SDK automatic retries are disabled.
6. After upload and input-stream closure succeed, `markReady` takes the shared
   hierarchy lock, freshly loads the owned `UPLOADING` row, checks its expected
   version, and revalidates destination accessibility.
7. Finalization uses an expected-status/version conditional update. An accessible
   destination permits `READY`. An inaccessible destination commits `FAILED`, then
   triggers best-effort object deletion outside the transaction.
8. A generic finalization exception retains the object because PostgreSQL might
   already have committed `READY`.
9. Successful completion returns HTTP 201. Upload failures wrapped as
   `FileUploadException` return HTTP 500; a lost response does not undo committed
   metadata.

Only active, accessible `READY` files are exposed as usable files. `UPLOADING`
reserves its active name; `FAILED` releases it. Status and the trash/purge lifecycle
are separate dimensions.

## Crash and timeout boundaries

| Boundary | Possible durable result | Recovery implication |
|---|---|---|
| Before reservation commits | Neither row nor allocation, or an uncertain reservation commit | Reload PostgreSQL. PUT cannot follow unless reservation returns successfully. |
| Reservation committed; stream not opened or admission not begun | `UPLOADING`, unused allocation | Safe expiry candidate after coordinated permanent write preclusion. |
| Admission transaction rolls back | Allocation remains unused | Same safe branch after fresh validation. |
| Admission commits; response is lost or process stops before PUT | `write_started=true`, possibly no object | Indistinguishable from an uncertain PUT after restart. Remains blocked. |
| PUT is connecting, streaming, or awaiting response | Admitted allocation; absent, incomplete, or completed storage effect | Timeout or interruption does not establish storage quiescence. |
| PUT succeeds; process stops before finalization | `UPLOADING` with completed bytes | Currently lacks durable PUT-success evidence. |
| PUT succeeds; input-stream closure throws | Best-effort transition to `FAILED` | `FAILED` can contain successfully uploaded bytes. |
| Upload throws; marking `FAILED` also fails | Usually stale `UPLOADING`; database outcome may be uncertain | Preserve allocation and bytes. |
| Finalization rolls back | `UPLOADING` and retained bytes | Retry only after fresh eligibility and sufficient success evidence. |
| Finalization commits; acknowledgement is lost | Could already be `READY` | Read authoritative state; never compensate by deleting. |
| Destination rejection commits `FAILED`; cleanup has not completed | Completed bytes may remain | Cleanup needs durable outcome tracking; current cleanup is best-effort. |
| Cleanup DELETE succeeds; process stops | `FAILED`, no durable deletion receipt | Database cannot distinguish successful cleanup from remaining bytes. |
| Response delivery fails after `READY` commits | Valid `READY` file despite client error | Client retry is a new upload, not replay of the original reservation. |
| Application restarts | Reservations and allocations survive | No stale-upload reconciler currently resumes them. Restart does not fence Garage. |

Client disconnection has no explicit application cancellation protocol. A
disconnect during multipart parsing may prevent entry into the service; after
service execution begins, the upload may continue. Exact servlet behavior is an
unverified assumption requiring HTTP-level tests. A disconnect, interruption, or
closed connection must never become proof that Garage cannot complete a request.

Uploads have finite connection/acquisition/socket limits, but no configured total
streamed-upload deadline. The worker's five-minute runtime limit applies to jobs,
not HTTP uploads. A failed/uncertain PUT does not cause object deletion; it causes
best-effort FAILED marking. Failure to mark FAILED preserves the original upload
failure and can leave UPLOADING metadata.

## Authoritative states, immutable identities, and evidence

| Evidence | What it establishes | What it does not establish |
|---|---|---|
| `files` status/version/lifecycle and ancestry | Current business eligibility | Object existence or writer termination |
| Retained allocation: file UUID, owner UUID, key, namespace, endpoint, region, bucket | Intended immutable storage allocation | Cryptographic identification of the Garage cluster or current bytes |
| `write_started=false` | No committed application write admission | Safety until a competing admission is atomically excluded |
| `write_started=true` | One admission was consumed | Whether PUT began, succeeded, failed, or remains in flight |
| `write_precluded_at` with unused allocation | Future application admission is permanently prohibited | Termination of an already-admitted writer |
| `READY` | Normal application flow finalized after upload success | Protection against independent storage writers |
| `FAILED` | Upload/finalization failed from the application's perspective | Object absence or writer quiescence |
| HEAD response | Storage observation at that moment | Ownership, a deletion fence, or absence of future writes |
| Purge receipts | A specifically validated purge outcome | General upload success or uncertain-writer termination |

V16 retains allocation identities without cascading resource foreign keys. Its
triggers prohibit identity changes and resetting write admission. Legacy rows
have consumed allocations with unknown bindings; their original location is not
inferred from current settings. V20/V21 add permanent preclusion and verified
never-written proof.

The allocation identity is the retained file/key tuple; there is no separate
durable upload-attempt identity or writer-incarnation record. Current PUT requests
attach no application allocation marker, and returned ETag/version information is
discarded. No durable expected content checksum, PUT-success receipt, admission
timestamp, or failure classification distinguishes unfinished cases.

Within the accepted trusted-writer contract, the exact retained allocation is the
main provenance evidence. Filename, size, content type, timestamps, ETag, or
key-prefix resemblance cannot independently establish ownership. The namespace is
a configured logical identity, not a Garage-verified cluster identifier.

## Concurrency, authorization, and purge constraints

- File trash, restore, rename, move, and standalone permanent-delete operate on
  eligible `READY` files. They cannot normally mutate an unfinished upload.
- Reupload creates a new UUID/key. There is no overwrite or resume endpoint.
  Same-name requests still contend through authoritative namespace constraints.
- Folder mutations can occur during transfer. Fresh ancestry validation determines
  finalization. A folder restored before finalization may again be accessible;
  once the upload is FAILED, normal code does not resurrect it. Recovery must not
  introduce restore-style root fallback.
- READY/FAILED finalization competitors use status/version CAS, so exactly one
  transition wins. Shared hierarchy locks alone do not serialize same-file writers.
- Allocations survive metadata deletion and prevent intentional application key
  reuse. They must remain permanent tombstones.
- Purge byte receipts and metadata-finalization receipts have separate meanings.
  Missing metadata or a successful job alone cannot invent new upload evidence.
  Existing purge `SUCCEEDED` semantics remain byte deletion/absence.
- Folder purge captures immutable lifecycle snapshots. Changing a member's status,
  version, or timestamps can invalidate sealed membership.
- Existing folder execution permanently precludes verified unused allocations
  and records never-written proof without DELETE. Admitted `UPLOADING` and `FAILED`
  allocations remain `UNSAFE_WRITER`, regardless of age.

Existing tests explicitly cover
[age-independent writer blockers and never-written proof](../backend/src/test/java/com/vaultdrive/worker/FolderPurgeFileIntegrationTest.java)
and [competing upload transitions/folder transfer-gap mutation](../backend/src/test/java/com/vaultdrive/file/FileHierarchyConcurrencyIntegrationTest.java).
Their presence is not a fresh passing result from this audit.

The initial recovery slice should defer files beneath pending-purge ancestry to
existing folder processing. It must not rewrite manifests, lifecycle snapshots,
purge targets, receipts, or terminal jobs.

Recovery must validate owner, file, allocation, full binding, key, expected
version, deletion state, and ancestry after coordination. Future user-facing
inspection must preserve existing non-leaking authorization behavior.

## HEAD/DELETE races and Garage 2.3.0 limits

The tagged PUT implementation lacks destination create-only enforcement. DELETE
dispatch is key-based and does not use a requested generation; its returned
version identifier supplies no generation-specific deletion fence. These limits
agree with the accepted [trusted-writer contract](file-purge.md#accepted-storage-contract).
See Garage's [compatibility documentation](https://garagehq.deuxfleurs.fr/documentation/reference-manual/s3-compatibility/),
tagged [PUT source](https://raw.githubusercontent.com/deuxfleurs-org/garage/v2.3.0/src/api/s3/put.rs),
and [DELETE dispatch](https://raw.githubusercontent.com/deuxfleurs-org/garage/v2.3.0/src/api/s3/api_server.rs).

Unsafe interleavings include:

1. HEAD observes absence -> cleanup records success -> an admitted, delayed PUT
   subsequently arrives.
2. HEAD observes object A -> another writer replaces it with B -> DELETE removes
   the current key.
3. Recovery prepares cleanup -> another path finalizes READY -> delayed DELETE
   removes its bytes.

Database tokens fence database outcomes, not external requests. Lease expiry, JVM
restart, successful cancellation, repeated HEAD observations, or longer waiting
does not provide storage-side fencing. Restart also does not establish that
another application instance has no writer.

Positive HEAD is stronger evidence than age: the tagged
[HEAD implementation](https://raw.githubusercontent.com/deuxfleurs-org/garage/v2.3.0/src/api/s3/get.rs)
returns completed object data. It remains an observation rather than a write
capability fence. A narrowly proven adoption protocol under the single-writer
contract might eventually be possible; current tests do not establish it, and it
should remain outside the smallest implementation.

**Automatic cleanup of an admitted uncertain writer cannot be made safe using
age plus HEAD/DELETE under current guarantees.** Stronger evidence must establish
both that the writer cannot issue a future request and that previously issued
storage work cannot subsequently create bytes.

Permanent nonreuse makes repeated DELETE address the same old allocation under
trusted writers. It does not make independent overwrites or namespace/alias
reassignment safe. Those remain accepted operational risks, not solved fencing.

## Proposed recovery decisions

| State | Proposed action |
|---|---|
| READY, including uncertain response delivery | Preserve; no stale-upload cleanup |
| Old UPLOADING + verified unused allocation | Atomically preclude admission and expire to FAILED |
| FAILED + verified unused allocation | Permanently preclude admission; retain evidence |
| Unused allocation beneath pending purge | Existing folder never-written-proof path |
| Admitted UPLOADING without durable success evidence | Record blocked disposition; preserve bytes and metadata |
| Admitted FAILED without durable terminal evidence | Record blocked disposition; preserve bytes and metadata |
| Future durable PUT success + unchanged eligible UPLOADING | Retry metadata finalization |
| Future durable PUT success + inaccessible destination | Commit FAILED and durable cleanup intent |
| Unknown binding, identity drift, or missing metadata without receipts | Quarantine for review |
| New upload after failure | New file/key only |

Quarantine means a retained database disposition. It must not copy, move,
overwrite, or delete Garage objects. Retry of metadata recovery is distinct from
retry of PUT.

## Smallest durable design proposal

These are proposed decisions, not implemented behavior or an accepted schema.

1. **Explicit bounded reporting service.** Select database-backed candidates only;
   no bucket listing, broker changes, automatic scheduling, HEAD-based mutation,
   or deletion.
2. **Expire only never-admitted reservations.** In one transaction, acquire
   hierarchy -> fresh file -> allocation locks; revalidate identity and
   eligibility; set permanent preclusion and conditionally change UPLOADING to
   FAILED. Persist the recovery disposition atomically. A competing `beginWrite`
   either wins admission first or loses to preclusion.
3. **Durable success evidence for new uploads.** After PUT returns and stream
   closure succeeds, commit an immutable success receipt in a separate short
   transaction before READY finalization. Bind it to the original allocation and
   expected metadata identity. If receipt persistence is uncertain, do not re-PUT
   or delete.
4. **Recover known successes conservatively.** Use the receipt to retry
   finalization after fresh validation. Commit rejection and cleanup intent
   atomically. Cleanup must use the persisted target, retain a separate deletion
   receipt, and permanently exclude READY adoption.
5. **Historical uncertain writes stay blocked.** No backfill derives success from
   `FAILED`, age, present objects, or absent objects.

The separate success transaction deliberately leaves one conservative gap: a
crash after successful PUT but before its receipt remains uncertain. Pretending
PostgreSQL and Garage commit atomically would not eliminate that gap safely.

### Eligibility, timeout, and scan rules

- Use PostgreSQL time for eligibility and new persisted recovery timestamps.
- Consider one hour an initial conservative never-admitted reservation expiry
  policy, subject to approval. It may reject a delayed request; it is not a
  storage-safety timeout or a measured tuning value.
- Historical file timestamps originate in application code. Treat their age as
  selection/policy evidence, never writer-termination evidence.
- No finite age makes an admitted uncertain upload safe to delete.
- Use immutable keyset ordering, such as reservation time plus UUID; advance past
  blocked candidates and wrap on later sweeps.
- Start with twenty candidates per page, maximum 100, and independent candidate
  transactions. Bound page runtime as well as row count.
- Reuse existing transaction/statement/lock timeout patterns: ten/five/two seconds.
- Bound any later storage calls independently. A storage timeout remains an
  uncertain outcome, not proof that the server stopped.

### Idempotency, concurrency, and checkpoints

For database-only expiry, row locks and CAS suffice; a new leased job system is
unnecessary. Keep a unique retained recovery identity for each allocation and
monotonic outcomes so duplicate calls cannot reset progress.

If later cleanup uses workers, reuse existing claim/checkpoint principles: claim
separately, perform I/O outside transactions, acquire the job fence last when
recording progress, and reject stale outcomes. Resource order remains hierarchy
-> fresh file -> allocation -> recovery/work -> final job fence where applicable.
No resource lock may be acquired after the job fence.

Crash-safe checkpoints distinguish:

- preclusion/expiry committed;
- PUT success recorded;
- READY finalization or cleanup decision committed;
- cleanup byte receipt committed;
- metadata disposition completed.

Replays preserve original identities and receipts. Unknown commit outcomes require
reload, not compensating deletion. Preclusion/expiry and its evidence commit
together; rollback restores both. Cleanup-effect loss before receipt permits
repeating only the same proven-safe target. A durable receipt permits skipping
the external operation. Metadata removal, if later introduced, requires its own
atomic retained proof.

## Schema assessment

Reporting needs no schema change. Existing V20 preclusion supports the core
never-admitted expiry transition.

The complete durable design needs an append-only migration for retained upload
outcome/recovery evidence and suitable scan indexes. Minimum concepts are:

- original allocation identity and expected metadata snapshot;
- database-timed PUT-success evidence;
- controlled recovery disposition and reason;
- mutually exclusive finalization/cleanup decisions;
- cleanup outcome evidence, if cleanup is introduced.

Constraints should prevent receipt replacement, identity changes, conflicting
decisions, and success without an admitted verified allocation. Exact fields,
retention, retry policy, and claim needs require an accepted design before DDL.
Permanent allocation tombstone retention remains mandatory.

Do not repurpose purge `SUCCEEDED`, fabricate purge events, or insert upload
cleanup into existing purge work without explicitly extending its contract.
Existing job types support only file/folder purge. No migration was created.

## Manual intervention and observability

Operator review should inspect exact allocation identity, current metadata and
ancestry, retained receipts/manifests, and independently recorded writer evidence.
Process death alone is insufficient. Unknown bindings require independently
verified original-location evidence; current settings are not historical proof.

Until an approved quiescence protocol exists, uncertain cases remain retained.
Do not reset admission, reuse keys, rewrite sealed manifests, fabricate receipts,
or reset terminal jobs. No manual destructive action is authorized by this audit.

Report bounded counters for scanned, expired, already-resolved, deferred-to-purge,
blocked-by-reason, failed transactions, and failed scans. Track unresolved age and
backlog. Logs should use controlled reason codes and correlation identifiers,
excluding credentials, payloads, raw exception text, object keys, and
high-cardinality metric labels. A reported blocked item is not a recovered upload
or completed deletion.

## Phased implementation and acceptance criteria

| Phase | Scope | Required acceptance |
|---|---|---|
| B1 | Bounded inventory and classification | Correct pagination; blocked rows cannot starve later rows; no storage mutations or autonomous enablement |
| B2 | Never-admitted expiry | Admission/preclusion races tested in both winner orders; expiry and evidence atomic; delayed requests cannot PUT afterward |
| B3 | Durable success receipts and finalization retry | Crash/rollback replay preserves receipts; no repeated PUT; READY and cleanup decisions cannot both win |
| B4 | Known-success cleanup, if separately accepted | Persisted exact target; durable intent before DELETE; repeatable receipt recovery; no cleanup without writer-terminal evidence |
| Deferred | Historical uncertain writers | Remain blocked until an explicit quiescence proof or stronger storage capability exists |

### Safety invariants

1. Never reuse an allocated key.
2. Never delete for a potentially writable allocation.
3. Never delete for a possibly committed READY outcome.
4. Never infer storage identity from current settings or HEAD.
5. Never hold hierarchy locks or database transactions across storage I/O.
6. Never bypass pending-purge barriers or invalidate sealed membership.
7. Never manufacture success from missing metadata, timeout, age, or job exhaustion.
8. Keep allocation identities and committed receipts after resource removal.
9. Preserve owner isolation, ancestor accessibility, and authoritative constraints.
10. Keep autonomous processing disabled by default.

### Test strategy

- Deterministic PostgreSQL races against admission, foreground READY/FAILED,
  folder trash/restore/purge, duplicate recovery, and name reuse after FAILED.
  Cover both winner orders and assert committed state, not only return values.
- Rollback, lock-wait expiry, identity/binding drift, unknown legacy allocation,
  missing metadata, pagination, interruption, and bounded scan failures.
- Late PUT after timeout/restart, misleading HEAD absence, PUT-success/input-close
  failure, and unknown finalization commit outcome.
- Success-receipt, cleanup-intent, DELETE, receipt, and finalization crash
  boundaries; duplicate execution must preserve monotonic evidence.
- Pending-purge files must retain exact snapshots; never-written proof and existing
  standalone/folder purge receipts must remain unchanged and valid.
- Real Garage checks use owned disposable allocations and assert unchanged control
  bytes. Do not infer provider behavior from mocks alone.
- Future process-loss tests need their own scoped authorization and isolation.
  Existing purge process-loss results do not substitute for uncertain PUT tests.
- Run focused checks and the full relevant suite before implementation commits;
  report unavailable or skipped infrastructure checks exactly.

## Open decisions and limitations

- Reservation expiry policy and user-visible handling of a delayed request.
- Exact retained receipt schema, indexes, retention, and recovery operation surface.
- Treatment of inaccessible known-success uploads, especially inside pending-purge
  trees; the initial slice must defer to existing purge coordination.
- Acceptable operator evidence for writer and storage quiescence. No such protocol
  is established by current code.
- Whether a later positive-object adoption protocol can be proven under the exact
  trusted-writer assumptions; HEAD-only adoption is not accepted by this proposal.
- Exact servlet disconnect behavior remains unverified.

No throughput, production-load, multi-node, or storage-side fencing claim is made.
All autonomous defaults remain disabled. The audit found a clean working tree and
no diff errors at the audited baseline; the subsequent documentation commit does
not change that code baseline or add runtime verification.
