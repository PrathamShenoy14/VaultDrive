# Upload success receipts and finalization recovery (Phase 2E.5B3)

Consolidation update: [the backend audit](backend-consolidation.md) retires synchronous upload admission/PUT and foreground receipt creation. Nonempty uploads use direct multipart. [ADR-0016](decisions/0016-zero-byte-files.md) later adds zero-only SERVER_PUT creation with one dispatch and positively verified V25 receipts, reusing the existing metadata-only finalization and frozen creation actor. Earlier flow descriptions, commands and verification totals below are historical milestone evidence; retained historical readers/receipts/expiry/cleanup remain supported where stated in the audit.

Follow-up: [B4B](known-success-upload-cleanup.md) now adds explicit cleanup only for
matching receipt-backed FAILED uploads. UPLOADING recovery and its restore behavior
remain unchanged. The original B3 implementation/evidence record below is historical.

## Design recorded before implementation

The traced foreground boundary is reservation transaction -> stream acquisition ->
adapter beginWrite transaction -> synchronous Garage PUT -> input-stream close ->
metadata finalization transaction. Admission and finalization do not surround I/O.
Previously the only success evidence between close and READY lived in memory;
destination rejection committed FAILED and attempted an untracked DELETE.

B3 retains the original allocation and adds admission metadata version/destination
and a database-generated PUT-success timestamp. The allocation's immutable file,
owner, key, namespace, endpoint, region and bucket identify the receipt; one
admission per allocation means no additional attempt UUID is necessary. Admission
captures the expected UPLOADING version and folder (null means root). Historical
admissions cannot acquire these fields retroactively. No ETag or HEAD is proof.

After PUT and close both succeed, a separate short transaction validates and
records success. It commits before another transaction attempts READY. Duplicate
recording preserves the original timestamp. Both use shared owner hierarchy ->
fresh scalar file FOR UPDATE -> original allocation FOR UPDATE. Receipt recording
does not require accessible ancestry: success remains useful evidence even when
a folder was trashed during transfer. Recovery revalidates bounded ancestry,
ownership, exact allocation/admission identity, version, lifecycle, expiry and
conflicting purge evidence before the existing status/version READY CAS.

Eligible UPLOADING becomes READY. Matching READY resolves replay. FAILED never
becomes READY. Inaccessible or pending-purge destinations remain blocked with
unchanged metadata and retained bytes; there is no cleanup decision or Garage
operation in B3. Foreground completion uses the same recovery transaction.

| Crash or uncertain outcome | Durable consequence |
|---|---|
| PUT not yet returned, or close failed | No success receipt; admitted outcome remains unknown (ordinary failure may mark FAILED) |
| PUT and close succeeded; receipt not committed | Success observed only in memory; after crash unknown forever in B3 |
| Receipt transaction rolled back | No receipt; preserve allocation/object and report unresolved |
| Receipt committed; acknowledgement lost | Fresh independent PostgreSQL reload may prove receipt; no repeated PUT or DELETE |
| Receipt committed; READY not attempted or rolled back | Explicit recovery can retry eligible metadata only |
| READY committed; acknowledgement lost | Replay reads matching READY and resolves idempotently |
| Eligibility changed or FAILED won | Report blocked; preserve receipt/object; never resurrect FAILED |

Acceptance requires real PostgreSQL constraints, independent transaction replay,
rollback and deterministic races, bounded keyset progress, no recovery storage
interactions, and the safely runnable backend regression suite. B4, historical
repair, presigned/multipart uploads and autonomous scheduling are excluded.

## Implemented schema and foreground boundary

[V25](../backend/src/main/resources/db/migration/V25__record_upload_put_success.sql)
adds `admitted_file_version`, `admitted_folder_id` and `put_succeeded_at` to
`file_storage_objects`. Existing primary/unique keys deduplicate receipts by
allocation key and file UUID; existing immutable binding triggers prohibit target
replacement. New checks require an admitted, fully bound, non-precluded,
non-expired allocation for evidence. A trigger permits the admission snapshot
only with the original false-to-true admission, validates matching UPLOADING
metadata, prohibits snapshot/receipt replacement or removal, and assigns the
first receipt timestamp using PostgreSQL `clock_timestamp()`. Null folder denotes
the admitted root destination; nullable version distinguishes historical admission.
No historical row is backfilled. Permanent allocation retention remains mandatory,
including after metadata deletion; there is no cascading resource foreign key.

[FileService](../backend/src/main/java/com/vaultdrive/file/FileService.java) calls
[completion](../backend/src/main/java/com/vaultdrive/file/UploadFinalizationRecovery.java)
only after try-with-resources exits successfully. Receipt persistence and the
authoritative uncertainty reload are independent REQUIRES_NEW transactions. The
reload verifies the exact original file/owner/key/version/folder and retained
admission/full binding. A committed receipt permits metadata recovery; failed or
absent evidence reports HTTP 500 through the existing FileUploadException mapping
and preserves the allocation/object. Receipt failure does not mark FAILED. PUT or
close failure retains the existing best-effort FAILED marking and creates no
receipt. There is no new PUT retry or compensating DELETE.

## Recovery authority and invocation

[Transactions](../backend/src/main/java/com/vaultdrive/file/UploadFinalizationTransactions.java)
read immutable owner identity only before locking, then re-read scalar metadata
and allocation after the shared hierarchy/file/allocation locks. Admission is
immutable on the same locked allocation; its complete retained binding is the
original binding, never reconstructed from current client configuration.

Matching READY with a version beyond the admitted version resolves idempotently,
including after legitimate later metadata operations. This is an observation,
not a fresh READY mutation or permission to interfere with subsequent purge.
For UPLOADING, current version and folder must exactly match admission. The owner
must still exist, lifecycle timestamps must be null, and a bounded owner-scoped
ancestry walk must reach root within 128 folders without cycles/missing edges.
Trash or purge in any ancestor blocks. Expiry evidence/preclusion, purge targets,
purge work and retained folder-purge membership prohibit READY adoption. The
existing repository status/version conditional update supplies the final CAS;
exactly one affected row is required. Failed transactions roll back that transition.

Exclusive folder mutations serialize with the shared recovery lock. B2 and
beginWrite use the same file/allocation order: an admitted receipt cannot be
expired, and an expired allocation cannot receive success evidence. Existing
folder purge still treats admitted UPLOADING/FAILED as UNSAFE_WRITER even when a
B3 receipt exists; B3 does not extend any purge proof or event contract.

Inject UploadFinalizationRecovery into a trusted internal integration, outside a
caller transaction:

```java
var page = recovery.recoverPage(null);
var next = recovery.recoverPage(page.nextCursor()); // Each call is explicit.
```

The cross-owner internal operation must not be exposed directly to untrusted
clients. No endpoint, startup runner, scheduler, automatic wrap/retry, persistent
cursor, outbox event or job is added. Normal autonomous component defaults remain
unchanged. A later sweep begins with null to revisit blockers or new receipts
behind the cursor. Old inventory classifications never authorize this operation.

`vaultdrive.uploads.finalization-recovery.page-size` defaults to 20 and accepts
1..100; invalid configuration fails startup. A partial receipt index supports
`file_id > cursor ORDER BY file_id LIMIT` selection, including receipts whose
metadata is absent or already READY. Every attempted candidate advances the
cursor, including blockers and transaction failures; earlier candidates cannot
starve later UUIDs. Exhaustion requires an empty selection. There is no age gate:
durable success plus current eligibility authorize metadata recovery, never age.

Selection is read-only; every candidate has an independent REQUIRES_NEW
transaction. All use ten-second Spring, five-second statement and two-second
lock-wait bounds. A monotonic ten-second page admission budget and interruption
check prevent starting further candidates. The current transaction can finish
after that budget; connection acquisition, network failure and JVM pauses prevent
a hard end-to-end latency guarantee. No performance claim is made.

The result exposes only UUID cursor, exhaustion and sanitized counters:
`scanned`, `recovered`, `alreadyReady`, `blocked`, `noReceipt`, `failed`,
`scanFailed`. Scanned equals the five candidate disposition counters. Failed
selection has zero attempts and preserves the incoming cursor. Failed transactions
can have an uncertain commit outcome; an explicit later invocation reloads state.
Blocked reports no cleanup authority or absent bytes. There are no storage keys,
owner labels, raw exceptions, credentials or payloads in page reporting/logging.

## Verification and limits

[PostgreSQL tests](../backend/src/test/java/com/vaultdrive/file/UploadFinalizationRecoveryIntegrationTest.java)
use a fresh random schema and committed disposable owners. They model crashes by
stopping at committed boundaries, then replay independently. They cover receipt
deduplication/immutability, lost acknowledgements, rollback, READY/FAILED CAS,
foreground/recovery/expiry and folder races in both orders, sealed membership,
unknown historical bindings, identity/version mismatch, pagination/caps and
timeouts. Storage service and S3 SDK receive zero calls in these fixtures.

[Live Garage tests](../backend/src/test/java/com/vaultdrive/file/UploadSuccessGarageIntegrationTest.java)
use another fresh schema and exclusively disposable allocations. They prove bytes
survive before/after receipt recovery and after foreground destination rejection;
SDK/service spies assert recovery performs no PUT, HEAD or DELETE. Fixture cleanup
registers only returned successful PUTs and deletes only those disposable writes.
Unexpected uncertain PUTs or cleanup failures retain the random schema/allocation
and fail verification. Stream-close ordering
and failed closure are covered by [service tests](../backend/src/test/java/com/vaultdrive/file/FileServiceTest.java).

This is committed-boundary/replay evidence, not abrupt JVM-kill testing. No
historical uncertain writer, FAILED resurrection, broad orphan cleanup,
storage-side fencing, independent external writer, endpoint reassignment,
multi-node failure or production-load guarantee is established. B4 is not started.

Reproduce the focused checks from `backend/`, using the ignored local environment
in the same PowerShell session:

```powershell
. .\.env.local.ps1
.\mvnw.cmd '-Dtest=UploadFinalizationRecoveryIntegrationTest,UploadFinalizationRecoveryTest,UploadSuccessGarageIntegrationTest,FileServiceTest,HierarchyHttpTransactionTest,StaleUploadExpiryIntegrationTest,StaleUploadInventoryIntegrationTest,FileHierarchyConcurrencyIntegrationTest' test
```

For the full suite, use the existing temporary localhost AMQP tunnel and
`RABBITMQ_TEST_VHOST=vaultdrive_outbox_test`, `ASYNC_E2E_TEST=true`,
`ASYNC_PROCESS_TEST=false` as documented by the
[continuous verification runbook](async-purge-verification.md). Run `mvnw.cmd test`
in the same environment. Default test contexts can additionally be scoped to a
new private `b3_regression_<UUID>` schema with the datasource currentSchema and
Flyway schemas/default-schema properties; class-specific isolation still applies.
Dispose only that exact task-owned schema after fixture byte cleanup succeeds.

Executed on 2026-10-17 against PostgreSQL 17.11 and the existing test services:

- Focused recovery/upload/expiry/inventory/hierarchy run: 163 passed. The added
  sealed-membership check passed separately; the updated HTTP suite passed all
  13 cases. The final three live Garage cases passed again after strengthening
  failure cleanup ownership/retention.
- Final full backend suite: **826 tests, 825 passed, zero failures/errors, one
  intentional skip** (`AsyncPurgeProcessRecoveryTest`, ASYNC_PROCESS_TEST=false).
  All seven live RabbitMQ tests and the continuous HTTP-to-Garage scenario passed.
  The latter removed its seven owned objects, queue, exchange and private schema.
- The first full run exposed the HTTP probe's old three-transaction expectation;
  it now checks four joined transactions and retained admission/success evidence.
  The subsequent full run passed. No production change was needed for that probe.
- V24-to-V25 compatibility, receipt/CAS rollback, lock and statement timeouts,
  concurrent winner orders, retained bytes and zero recovery storage interactions
  passed. All task-owned random schemas were verified absent after cleanup; the
  extra default-context regression schema contained only six never-admitted
  allocations before disposal. Existing fixed integration schemas were retained.
- Documentation links, whitespace, complete diff and private-file exclusions were
  reviewed. No development data, pre-existing Garage objects, credentials, VM
  configuration, broker configuration or autonomous defaults were changed. Only
  task-owned local AMQP forwarding was opened and closed.
