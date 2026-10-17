# Restricted known-success upload cleanup (Phase 2E.5B4B)

B4B implements the restricted GO in the [preserved B4 audit](known-success-cleanup-design-audit.md),
using the [B4A single-dispatch contract](single-put-dispatch-verification.md).
It is an explicit internal operation for receipt-backed FAILED uploads. It does
not schedule cleanup or extend the folder-purge proof protocol.

## Eligibility and authority

Before committing new authority, acquire shared owner hierarchy -> fresh scalar
file FOR UPDATE -> original allocation FOR UPDATE. Require all of:

- Existing owned FAILED metadata, null deletion/purge timestamps, an existing
  owner, and exact file UUID/owner/storage-key agreement with its allocation.
- Genuine immutable V25 success evidence with consumed admission, full retained
  namespace/endpoint/region/bucket, and no preclusion or reservation expiry.
- Current version exactly `admitted_file_version + 1`, and the original admitted
  folder (including root). Only the single UPLOADING-to-FAILED transition is an
  accepted history; additional version drift is rejected.
- The retained binding matches the configured storage client. Unknown historical
  bindings are never reconstructed from current settings.
- Owner-scoped ancestry reaches root within 128 folders without missing edges,
  cycles, trash or pending purge. Root needs no folder row.
- No file purge target, shared work/byte/removal receipt, or folder file membership
  exists. Pending ancestry also excludes legacy/unsealed folder intents.

UPLOADING (even with a receipt), READY, historical/admitted-uncertain writes,
missing or mismatched metadata, inaccessible ancestry and purge-owned candidates
receive no new intent or DELETE. Age, HEAD, process death, cancellation and retry
exhaustion never establish writer termination. Ordinary trashed ancestry requires
restore before a new decision. A small or empty eligible population is expected:
the foreground B3 path normally leaves rejected destinations UPLOADING.

[V26](../backend/src/main/resources/db/migration/V26__known_success_upload_cleanup.sql)
retains one immutable intent per allocation, the complete accepted metadata JSONB
snapshot (timestamp fields use timezone-independent epoch values), original V25
receipt timestamp, controlled reason and PostgreSQL decision
time. Its non-cascading allocation FK plus permanent intent forbids allocation
removal. The referenced allocation permanently retains file/owner/key/full binding
and admission version/folder; these are the original target and receipt identity.
No allocation or metadata is removed by cleanup, and no V20 preclusion is fabricated.

Intent insertion is the business abandonment decision, atomic with its initial
claim progress. FAILED needs no further metadata transition. A PostgreSQL trigger
validates the snapshot/receipt and locks file then allocation; another guards every
file INSERT/UPDATE against changing the accepted snapshot after abandonment.
This fences foreground/B3 recovery, retained `markReady`, direct JPA status writes
and attempted metadata reuse. Existing status/version CAS remains authoritative.
Rollback removes intent/progress together and leaves metadata/allocation unchanged.

## Durable state and execution

```text
receipt-backed FAILED --validated transaction--> immutable intent + live claim
    --> DELETE outside all transactions/locks
    --> immutable DELETED_OR_ABSENT receipt in a new fenced transaction

storage failure --> retained intent + retry_after --> explicit later claim
crash/unknown outcome --> retained intent + expired lease/backoff --> explicit later claim
```

[Transactions](../backend/src/main/java/com/vaultdrive/file/KnownSuccessCleanupTransactions.java)
use independent REQUIRES_NEW boundaries with ten-second transaction, five-second
statement and two-second lock-wait bounds. Order is shared hierarchy -> fresh file
-> allocation -> retained intent/progress -> claim lock/fence last. The intent's
parent/FK key-share lock is acquired before the claim lock. Only immutable
owner identity is read before hierarchy coordination. No resource lock is acquired
after the claim fence. Database time is checked after acquiring that lock.

Claims use a fresh UUID token, monotonic revision and nonrenewing 30-second lease.
The crash retry deadline is durably set to lease plus one second; known storage
failures release the claim and persist a one-second retry delay and controlled
`STORAGE_FAILURE` reason. Retries have no terminal exhaustion: each explicit
invocation is bounded, while the original authority stays recoverable. There is
no automatic retry loop. Late workers cannot record a receipt or failure after
lease expiry/replacement. Successful replay observes the existing immutable receipt.

The orchestration invokes only `ObjectStorageService.delete(StorageTarget)` with
the original retained binding. The adapter rejects mismatched configuration and
uses its existing ten-second call/five-second attempt bounds, disabled SDK retries,
and exact NoSuchKey absence handling. No HEAD precedes DELETE. A successful receipt
means DELETE returned success or the adapter verified NoSuchKey; it is separate
from PUT evidence and all purge receipts.

| Crash or uncertain acknowledgement | Explicit retry behavior |
|---|---|
| Intent transaction rolls back | No authority; no DELETE; revalidate from scratch |
| Intent commits but acknowledgement is lost | No DELETE from that failed preparation; recover claim after lease/backoff |
| Crash before DELETE | Retain authority; claim later and delete the same target |
| DELETE succeeds; crash before receipt | Repeat DELETE/absence for the permanently abandoned target |
| Storage failure or ambiguous response | No deletion receipt; retain retry state |
| Receipt rolls back or acknowledgement is unknown | Reload on the next call; durable receipt skips DELETE, otherwise lease recovery repeats it |
| Receipt committed | Observe completion; never replace its time or identity |

Claims fence database outcomes, **not external DELETE**. An expired worker can
still issue or finish DELETE. This remains safe only because the sole original
trusted writer finished, the allocation/key is permanently abandoned and retained,
and every READY path is fenced. This is not Garage generation fencing. Independent
overwrites, proxy replays, namespace/endpoint reassignment, or bypassing admission
violate the accepted operational assumptions. Preserve the B4A tests when changing
SDK/transport configuration or adding upload paths.

Folder mutations use exclusive hierarchy coordination, so new intent acceptance
sees their committed result. If cleanup wins before later folder trash/purge, its
already committed target remains abandoned and may finish. Later discovery may
snapshot the unchanged FAILED row, but B4B never edits membership, claims folder
completion or fabricates shared work/never-written/byte-removal evidence. The folder
protocol continues to block admitted non-READY members as UNSAFE_WRITER, including
ones with a B4B deletion receipt. Extending that proof is deferred.

## Operator invocation and bounds

Inject [KnownSuccessCleanup](../backend/src/main/java/com/vaultdrive/file/KnownSuccessCleanup.java)
into a trusted internal integration and call outside a transaction:

```java
var page = cleanup.cleanupPage(null);
var next = cleanup.cleanupPage(page.nextCursor()); // Every page is explicitly invoked.
```

This cross-owner operation must not be exposed directly to untrusted clients.
There is no HTTP endpoint, startup runner, scheduler, broker event/job, persistent
cursor, automatic wrap or autonomous enablement. All four existing async defaults
remain false. `vaultdrive.uploads.known-success-cleanup.page-size` defaults to 20,
accepts 1..100 and rejects invalid startup configuration.

Selection unions receipt-backed FAILED allocation UUIDs and retained intents,
ordered by immutable `file_id > cursor`. Every attempted candidate advances the
cursor, including blocked/busy/failed ones. Start a later sweep at null to revisit
blocked candidates and newly committed receipts behind the cursor. Exhaustion is
an empty selection. A monotonic ten-second admission budget and interruption check
stop starting candidates; the current candidate can finish afterward. Connection
acquisition, network faults and JVM pauses prevent a hard total latency promise.
No performance improvement is claimed.

Pages contain only UUID cursor, exhaustion and sanitized counters: `scanned`,
`deleted`, `alreadyComplete`, `blocked`, `busy`, `failed`, `scanFailed`. Scanned is
the sum of candidate outcomes. A failed scan preserves the incoming cursor.
Deletion counts require a confirmed database result; a lost acknowledgement may
report failure despite a committed receipt. No raw exceptions, credentials, keys,
owner labels or payloads are logged or returned. Internal claim capabilities
contain storage identity and must not be logged or serialized to clients.

## Verification and remaining limits

[Integration tests](../backend/src/test/java/com/vaultdrive/file/KnownSuccessCleanupIntegrationTest.java)
use a random private PostgreSQL schema and disposable successful Garage writes.
They retain allocation/intent/receipt evidence until all fixture bytes are cleaned,
then dispose only the new class-owned schema. Unexpected uncertain writes or byte
cleanup failures retain that schema. Previously retained regression schemas are
never selected for disposal. Crash checks stop at committed boundaries and replay;
they do not kill a JVM or prove broker/VM failover, multi-node behavior or load.

Focused command from `backend/`, with the ignored local environment loaded:

```powershell
. .\.env.local.ps1
$schema = 'b4b_focus_' + [guid]::NewGuid().ToString('N')
.\mvnw.cmd '-Dtest=KnownSuccessCleanupIntegrationTest,KnownSuccessCleanupTest,SinglePutDispatchIntegrationTest,UploadFinalizationRecoveryIntegrationTest,UploadSuccessGarageIntegrationTest,FileHierarchyConcurrencyIntegrationTest' `
  "-Dspring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=$schema" `
  "-Dspring.flyway.schemas=$schema" "-Dspring.flyway.default-schema=$schema" test
```

Run the complete backend suite in a fresh default regression schema with the
existing [isolated broker/E2E opt-ins](async-purge-verification.md#reproduce-from-windows-powershell).
When needed, use its explicitly documented `ASYNC_E2E_ALIGN_INITIAL_DUE=true`
test-only scheduling option for the observed Windows/VM clock skew. This does not
verify production initial scheduling under skew or change cleanup eligibility.
Keep `ASYNC_PROCESS_TEST=false`: this task does not authorize abrupt process-loss
experiments. Include all seven Garage-mutating classes excluded by B4A. Retain
the new default regression schema for operator review; do not remove old schemas.

```powershell
$env:RABBITMQ_TEST_VHOST = 'vaultdrive_outbox_test'
$env:ASYNC_E2E_TEST = 'true'
$env:ASYNC_PROCESS_TEST = 'false'
$env:ASYNC_E2E_ALIGN_INITIAL_DUE = 'true' # Only for the observed clock-skew condition.
$schema = 'b4b_regression_' + [guid]::NewGuid().ToString('N')
.\mvnw.cmd "-Dspring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=$schema" `
  "-Dspring.flyway.schemas=$schema" "-Dspring.flyway.default-schema=$schema" test
```

The focused command also isolates legacy tests that otherwise use the shared
`vaultdrive_test.public` default. An early B4B run applied provisional V26 there;
after the timezone comparison correction, a later unisolated legacy context
correctly failed checksum validation. No repair, schema disposal or history edit
was performed. Retain that shared test schema and use the fresh-schema commands
above; resolving its provisional migration requires a separate operator decision.

Historical uncertain writes, PUT success without receipt commit, stream-close
failure, missing metadata without retained authority, purge integration, metadata
removal, autonomous scheduling, independent writers and storage-side fencing remain
blocked/deferred. Stop after this restricted B4B slice.

### Executed verification (2026-10-17)

- Final isolated focused run: **127 passed**, including 50 new PostgreSQL/Garage
  integration cases, three orchestration unit cases and all twenty B4A dispatch
  cases. Eligible objects disappeared, duplicates skipped DELETE, stale claims
  could not persist new outcomes, interrupted effects replayed safely, unrelated
  controls stayed readable and delayed uncertain PUTs received no cleanup.
- Broker/E2E fixture correction run: **10 passed**. The final complete backend
  run passed **899 tests: 898 passed, zero failures/errors, one intentional skip**
  (`AsyncPurgeProcessRecoveryTest`, `ASYNC_PROCESS_TEST=false`). All seven previously
  excluded Garage-mutating classes and all seven live broker cases ran. The HTTP
  harness used the explicit initial-due alignment option for both events, reached
  file success and all four folder phases, preserved readable controls and removed
  its seven owned objects, queue, exchange and schema.
- An earlier full run had four failures and one error, all from initial publisher
  eligibility under confirmed JVM/VM clock skew. The two live broker fixture helpers
  now use PostgreSQL time; the E2E adjustment is test-only and opt-in. No production
  timing or clock/infrastructure setting changed. The final full rerun passed.
- Read-only PostgreSQL postflight confirmed **33 task-created disposable schemas
  absent** across the runs. Class-owned Garage cleanup verified absence for every
  registered successful write before disposing of its schema. No development data
  or pre-existing Garage object was selected for deletion.
- Three new default-context schemas remain retained, each containing three
  never-admitted allocations, zero PUT receipts, files, events and jobs:
  `b4b_focus_23269870a66849bc90630088355ac6cf`,
  `b4b_regression_ba138b951ec14853b228c1b0c85abc6b`, and
  `b4b_regression_df3a33b71b3d4aa3950af59e54064075`.
  Both prior `b4a_regression_` schemas also remain present. The shared test `public`
  schema retains its provisional V26 checksum and pre-existing admitted allocations;
  no repair, retained-data removal or uncertain-writer cleanup was attempted.
- Migration upgrade/constraints, transaction rollback, timezone-independent
  snapshots, deterministic blocking/winner orders, bounded waits, documentation
  links, full diff and private-file exclusions were reviewed. The original B4
  audit and production SDK/transport configuration are unchanged. Normal processing
  defaults remain disabled; no autonomous cleanup was introduced.
