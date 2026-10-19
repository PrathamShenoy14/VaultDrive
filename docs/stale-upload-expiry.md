# Never-admitted reservation expiry (Phase 2E.5B2)

Consolidation update: [the backend audit](backend-consolidation.md) retires synchronous upload admission/PUT and foreground receipt creation. New uploads use direct multipart only. Earlier flow descriptions, commands and verification totals below are historical milestone evidence; retained historical readers/receipts/expiry/cleanup remain supported where stated in the audit.

B2 adds an explicit PostgreSQL-only operation under the
[recovery audit](stale-upload-recovery-audit.md). It releases stale upload names
only after permanently excluding application write admission. It performs no
Garage PUT, HEAD or DELETE and adds no scheduler, endpoint, job or outbox event.
B1's [inventory](stale-upload-inventory.md) remains read-only. B3 now adds
[success evidence and conservative metadata recovery](upload-success-recovery.md);
B4 cleanup remains unimplemented.

## Invocation and bounds

Inject [StaleUploadExpiry](../backend/src/main/java/com/vaultdrive/file/StaleUploadExpiry.java)
in a trusted internal integration, outside any caller transaction:

```java
var page = expiry.expirePage(null);
// Save nextCursor privately; issue each subsequent page explicitly.
var next = expiry.expirePage(page.nextCursor());
```

Stop at `exhausted=true`, which requires an empty selection. A partial page does
not establish exhaustion. Start later sweeps with null to revisit blocked/failed
rows or insertions/aging behind the cursor. No automatic wrap, retries, persistent
cursor, startup runner or autonomous invocation exists. This cross-owner internal
operation must not be exposed directly to untrusted clients.

| Property | Default | Allowed |
|---|---|---|
| `vaultdrive.uploads.expiry.minimum-age` | `1h` | `1s` through `365d` |
| `vaultdrive.uploads.expiry.page-size` | `20` | `1` through `100` |

Invalid configuration fails startup. These settings are independent of inventory
settings. Age compares `files.created_at` to PostgreSQL `statement_timestamp()`
minus minimum age, both at selection and after locking. Historical creation times
originate in application code. Age permits rejection of a delayed request; it is
never evidence of absent bytes or termination of an admitted writer.

One read-only selection materializes at most one page of aged UPLOADING UUIDs,
using B1's `id > cursor ORDER BY id LIMIT` pattern. FAILED is outside B2's mutation
scope. Each candidate uses an independent REQUIRES_NEW transaction. Every attempted
candidate advances the cursor, including failed transactions and blockers, so they
cannot starve later UUIDs. No next attempt starts after interruption or a monotonic
ten-second page admission budget. The current transaction can finish afterward.
Each transaction has a ten-second Spring timeout, five-second PostgreSQL statement
timeout and two-second lock-wait timeout. Connection acquisition/network/JVM delays
are not a hard end-to-end latency guarantee. Queries can examine more rows than
LIMIT returns; no performance or production-load claim is made.

## Fresh eligibility and atomic transition

[Expiry transactions](../backend/src/main/java/com/vaultdrive/file/StaleUploadExpiryTransactions.java)
read only owner identity before coordination, then acquire:

1. Shared owner hierarchy advisory lock through the unchanged coordinator.
2. Fresh scalar file row `FOR UPDATE`, verifying owner and capturing its current version.
3. Retained allocation row `FOR UPDATE`.

Under those locks, revalidate all current metadata/allocation/ancestry state.
Inventory results, cached entities and prior selections confer no authority.
The file must remain UPLOADING with its current expected version, existing owner,
null deletion/purge timestamps and sufficient database-clock age. The original
allocation must have the same file UUID, owner and storage key, a complete retained
namespace/endpoint/region/bucket, no previous expiry receipt, and `write_started=false`.
The full binding is that allocation's immutable retained binding; it is neither
reconstructed from nor required to equal today's client configuration. No external
target is executed. The receipt stays on that exact allocation, so its full binding
cannot be substituted between validation, preclusion and receipt persistence.

An owner-scoped recursive ancestry walk must reach a root within 128 folders,
without cycles, missing edges or cross-owner edges. All ancestors must be active
and have no pending purge. B2 conservatively rejects ordinary trashed ancestry;
a later explicit sweep after restore can reconsider it. No root fallback occurs.
Any otherwise consistent own/ancestor purge intent defers to existing purge
processing before the accessibility check. Invalid identity/structural ancestry
takes precedence over purge deferral. Sealed manifests and receipts are untouched.

The same transaction permanently sets V20 `write_precluded_at` (preserving any
existing preclusion), conditionally updates UPLOADING to FAILED with expected
owner/status/version/age/lifecycle and increments version, then writes expiry
evidence. Every write requires exactly one affected row. Failure before commit,
CAS rejection, statement/lock timeout or rollback restores metadata, preclusion
and evidence together. A lost commit acknowledgement requires a fresh read/replay;
the database can contain all three committed changes, never a partial expiry.
All locks release at commit/rollback; no transaction spans storage I/O.

`beginWrite` has the same hierarchy/file/allocation order. Admission first commits
`write_started=true`; expiry refuses it. Expiry first commits permanent preclusion
and FAILED; delayed admission fails at its fresh file eligibility check before the
adapter can issue PUT. V20 also prevents admission even if metadata eligibility
were accidentally restored. This fences future application admission, never an
already admitted external request. Normal finalization retains its status/version
CAS: READY first is preserved; expiry first rejects stale finalization. Duplicate
expiry yields one transition/receipt and then `alreadyResolved`.

Folder trash/restore/purge use the exclusive hierarchy lock. Expiry revalidates the
committed winner's ancestry; folder operations wait when expiry holds shared
coordination. Pending-purge files never receive a B2 lifecycle/version mutation,
so discovery/sealed snapshots remain valid. A FAILED name is available to a new
reservation using a new UUID/key. The old allocation is permanently retained and
its key cannot be intentionally reused, including after metadata removal.

## Durable evidence and sanitized results

Existing FAILED status and V20 preclusion cannot distinguish ordinary upload
failure or folder never-written processing from deliberate expiry. The append-only
[V24 migration](../backend/src/main/resources/db/migration/V24__record_never_admitted_reservation_expiry.sql)
therefore adds two nullable fields to the retained allocation:

- `reservation_expired_at`: PostgreSQL time of intentional B2 expiry.
- `expired_file_version`: resulting FAILED metadata version.

Constraints require both fields together, an unused permanently precluded
allocation and an expiry time at/after preclusion. A trigger verifies matching
owned FAILED metadata/key/version when evidence is first recorded and prohibits
replacement/removal of established evidence. Existing allocation identity,
admission and preclusion guards remain unchanged. Evidence survives metadata
deletion on the retained allocation; no metadata FK/cascade, purge receipt or
fabricated PUT-success evidence is introduced. Existing rows receive no backfill.

Page results contain only counters, UUID cursor and exhaustion:

| Counter | Meaning |
|---|---|
| `scanned` | Attempted candidate transactions |
| `expired` | Committed preclusion, FAILED transition and evidence |
| `alreadyResolved` | Current READY/FAILED, or no longer old enough after selection |
| `admissionWon` | Otherwise eligible allocation already admitted a write |
| `deferredToPurge` | Valid identity/ancestry with own or ancestor purge intent |
| `inconsistent` | Missing/unknown/mismatched allocation, owner/structural/lifecycle/accessibility issue |
| `failed` | Candidate invocation threw, including unknown commit acknowledgement; cursor advances |
| `scanFailed` | Failed selection, zero attempts, unchanged incoming cursor |

`scanned` equals expired + alreadyResolved + admissionWon + deferredToPurge +
inconsistent + failed. These are page results, not backlog totals. The service emits
no logs, raw exceptions, storage keys, payloads, JWTs or credentials. A blocked
result proves no recovery, storage absence or writer quiescence.

## Verification and remaining limits

[Integration tests](../backend/src/test/java/com/vaultdrive/file/StaleUploadExpiryIntegrationTest.java)
use committed disposable fixtures in a random private `vaultdrive_test` schema.
The deterministic races hold a real operation's transaction open and observe
`pg_blocking_pids` before release. Both admission/expiry and finalization orders,
concurrent expiry, folder trash/restore/purge orders, duplicate replay, rollback
after preclusion and after metadata mutation, hierarchy/file/allocation/table lock
timeouts, explicit retry, fresh version/age, allocation/ancestry corruption, names,
retained immutable evidence and bounded cursor progress are covered. Storage
service and SDK have zero interactions; the real adapter's delayed upload call
is rejected before SDK PUT. Schema cleanup owns only the generated private schema.

Reproduce from `backend/` after loading the ignored local environment:

```powershell
. .\.env.local.ps1
.\mvnw.cmd '-Dtest=StaleUploadExpiryIntegrationTest,StaleUploadInventoryIntegrationTest' test
$env:RABBITMQ_TEST_VHOST = 'vaultdrive_outbox_test'
$env:ASYNC_E2E_TEST = 'true'
.\mvnw.cmd test
```

Use the existing temporary localhost AMQP tunnel described in the
[continuous verification runbook](async-purge-verification.md). Normal publisher,
consumer, worker and reconciliation defaults remain disabled. The separately
authorized process-kill matrix is not rerun by these commands.

Executed on 2026-10-17 against PostgreSQL 17.11 and the existing test infrastructure:

- Final focused expiry suite: **35 tests passed, zero failures/errors/skips**.
- Related inventory, hierarchy and folder purge verification: **138 tests passed,
  zero failures/errors/skips**.
- Full backend suite: **775 tests, 774 passed, zero failures/errors, one skip**.
  All seven live RabbitMQ tests, Garage integration and the isolated continuous
  HTTP-to-Garage pipeline passed with the test-vhost/E2E opt-ins above.
  `ASYNC_PROCESS_TEST=false` intentionally skipped `AsyncPurgeProcessRecoveryTest`;
  no new abrupt-process-loss or uncertain-PUT experiment was performed.
- Both admission/preclusion orders, rollback, timeout/retry, idempotency, sealed
  snapshot preservation and zero expiry storage-service/SDK interactions passed.
  Read-only PostgreSQL inspection confirmed removal of all seven exact random
  inventory/expiry schemas identified in this task's test logs. Continuous test
  cleanup confirmed its seven owned objects, schema, queue and exchange removed.
- Local documentation file links, whitespace and private-value exclusions passed.
  Development data, existing Garage objects, credentials, VM infrastructure and
  autonomous component defaults were unchanged. Only a temporary local AMQP SSH
  forward was used for the live broker checks.

Admitted UPLOADING and FAILED writers remain retained regardless of age, timeout,
process death or HEAD absence. B2 itself adds no PUT-success receipt, admitted
recovery, orphan scan, Garage cleanup, autonomous expiry, user-facing operation or
process-loss experiment. Independent external writers and namespace/endpoint
reassignment remain outside the trusted-writer guarantee. B3 is documented
separately in the [success recovery contract](upload-success-recovery.md).
