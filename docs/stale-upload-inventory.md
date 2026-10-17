# Stale-upload inventory (Phase 2E.5B1)

B1 implements bounded, read-only PostgreSQL reporting under the
[recovery audit](stale-upload-recovery-audit.md). B2 expiry, B3 success receipts/retry
and B4 cleanup remain unimplemented. No upload is expired, recovered or deleted.

## Explicit invocation and configuration

Inject [StaleUploadInventory](../backend/src/main/java/com/vaultdrive/file/StaleUploadInventory.java)
into an internal operator integration or invoke it from a Spring integration test:

```java
// Outside any caller transaction; no controller, scheduler or startup runner.
var page = inventory.scanPage(null);
// Report counters. Save nextCursor privately for a later explicit call.
var nextPage = inventory.scanPage(page.nextCursor());
```

Each call inspects at most one page. Stop when `exhausted` is true; a short or
partially inspected page alone does not establish exhaustion. Empty selection
returns `exhausted=true` and retains the cursor. Start a later sweep with null;
there is no automatic wrap, retry, persistent cursor, polling or background
enablement. Concurrent insertions/aging behind a cursor require a later sweep.
This internal cross-owner reporting service is not a user-authorized API and must
not be exposed directly to untrusted clients.

| Configuration property | Default | Allowed |
|---|---|---|
| `vaultdrive.uploads.inventory.minimum-age` | `1h` | `1s` through `365d` |
| `vaultdrive.uploads.inventory.page-size` | `20` | `1` through `100` |

Spring duration syntax applies; invalid configuration fails startup. The threshold
is reporting policy, never a storage safety timeout. Selection compares
`files.created_at` with PostgreSQL `statement_timestamp()` minus the configured
age. Historical creation times originate in application code and are not evidence
of writer termination. No enabled flag is needed: nothing calls B1 autonomously.

## Classification precedence and evidence

Selection includes aged `UPLOADING` and `FAILED` metadata ordered by immutable
file UUID. Each candidate is inspected again using one fresh statement snapshot
containing current metadata, retained allocation and owner-scoped folder ancestry.
This is an observation, not a locked recovery decision. Apply these rules in order:

1. **ALREADY_RESOLVED:** current metadata is `READY`. It left the unfinished set
   between selection and inspection. This proves current metadata resolution only;
   it neither independently verifies bytes nor validates every READY allocation.
2. **INCONSISTENT:** metadata disappeared, status is unsupported, allocation is
   missing, file/owner/key identity differs, or any retained binding component
   (namespace, endpoint, region, bucket) is unknown. Also includes an unfinished
   file unexpectedly in standalone Trash without purge intent, or ancestry that
   cannot reach an owned root within 128 folders (missing/cross-owner edges,
   cycles, depth overflow). Missing metadata alone is never invented success.
   This takes precedence over purge deferral.
3. **DEFERRED_TO_PURGE:** otherwise consistent unfinished metadata has its own
   purge intent or an owned ancestor (including its containing folder) has pending
   purge intent. Existing purge processing owns this case. B1 does not inspect,
   rewrite or certify sealed manifests, jobs or purge receipts.
4. **NEVER_ADMITTED:** otherwise consistent unfinished metadata has an allocation
   with `write_started=false`, including an already precluded allocation. It proves
   no committed application admission at this snapshot. It does not prove current
   accessibility, object absence or immediate expiry safety. B2 must still atomically
   preclude competing admission after fresh locked validation. Ordinary trashed
   ancestors do not change this admission evidence.
5. **ADMITTED_UNCERTAIN:** remaining allocations have `write_started=true`.
   There is currently no durable upload terminal/success evidence for these
   unfinished uploads. Admission may precede an unstarted, active, failed or
   successful PUT. FAILED proves neither absent bytes nor a stopped writer.

Retained full bindings are historical evidence: B1 neither compares them with nor
reconstructs them from current configuration. A complete historical binding can
be reported even if today's client could not execute it. Purge receipts are not
upload-success receipts. Age, restarts and HEAD observations cannot make an
admitted uncertain write a safe recovery candidate.

## Bounds, pagination and counters

[Transactions](../backend/src/main/java/com/vaultdrive/file/StaleUploadInventoryTransactions.java)
select at most the configured page size using `id > :after ORDER BY id LIMIT`.
The existing UUID primary-key index supports this order; no migration is introduced. Age and status
are filters, never cursor components. Resolving/deleting earlier candidates cannot
shift offsets or skip later UUIDs. Every attempted candidate advances the cursor,
including inconsistent/deferred candidates and failed inspections. Unattempted
candidates remain after the cursor. Later sweeps revisit changes behind it.

One selection and at most 100 independent inspections run per call. Only one UUID
page is materialized; each ancestry walk contains at most 128 rows. Transactions
use REQUIRES_NEW, a ten-second Spring timeout, PostgreSQL READ ONLY, five-second
statement timeout and two-second lock-wait timeout. No hierarchy or row locks are
acquired. READ COMMITTED can observe old committed state during an uncommitted
write, then new state after commit. Existing writer locks/version checks remain.

A monotonic ten-second page admission budget includes selection time. No next
inspection starts after the deadline or interruption. The current transaction may
finish afterward within its own bounds. This is not a hard end-to-end latency
guarantee for connection acquisition, JVM pauses or network failure. Queries may
examine more rows than LIMIT returns; statement timeouts bound that work. Failed
selection retains the incoming cursor and does not retry. No production-load,
throughput or query-plan performance claim is made.

The service returns sanitized page counters with no owner/key/credential labels:

- `scanned`: attempted candidate inspections, successful or failed.
- `neverAdmitted`, `admittedUncertain`, `deferredToPurge`, `inconsistent`,
  `alreadyResolved`: successful observations in each category.
- `scanFailed`: one failed selection (zero scanned), or the number of failed
  inspection transactions. Failures are not reclassified as inconsistent.

After successful selection, scanned equals classification counts plus scanFailed.
These are page counts, not backlog totals or recovered-upload counts. B1 emits no
logs and returns only counters and a UUID cursor; it exposes no raw exception
text, storage key, payload, JWT or credential.

## Safety and verification

Production SQL consists only of SELECT and transaction-local read-only/timeout
setup. There is no storage dependency/call, DML, migration, recovery disposition,
receipt, outbox or job write. PostgreSQL enforces read-only independently of ORM
hints. No transaction or hierarchy lock spans storage I/O.

[Focused tests](../backend/src/test/java/com/vaultdrive/file/StaleUploadInventoryIntegrationTest.java)
use a random private schema in `vaultdrive_test`, disposable per-test owners and
schema removal afterward. They cover age/configuration, unused/admitted/FAILED
allocations, missing/mismatched identities, unknown/historical bindings, purge
ancestry, cycles, admission/finalization across commit, selection/inspection state
changes, UUID pagination under resolution, the 100-row cap, inspection failure,
table-lock timeout and repeated scans. All-business-table snapshots remain equal;
PostgreSQL rejects an attempted test write under inventory read-only setup.
ObjectStorageService and SDK S3Client have zero interactions throughout these
fixtures and scans. No development rows or pre-existing Garage objects are used.

Reproduce from `backend/`:

```powershell
. .\.env.local.ps1
.\mvnw.cmd '-Dtest=StaleUploadInventoryIntegrationTest' test
```

Executed on 2026-10-17 against PostgreSQL 17.11 and the existing isolated test
infrastructure:

- Focused inventory suite: **20 tests, zero failures/errors/skips**.
- Full backend suite: **740 tests, 739 passed, zero failures/errors, one skip**.
  `RABBITMQ_TEST_VHOST=vaultdrive_outbox_test` and `ASYNC_E2E_TEST=true` enabled
  the seven live RabbitMQ tests and isolated continuous HTTP-to-Garage test.
  Garage integration and existing purge/concurrency regressions passed.
  `ASYNC_PROCESS_TEST=false` intentionally skipped the process-kill harness;
  no process-loss matrix was rerun for this read-only slice.
- All-business-table equality and zero storage-service/SDK interactions passed.
  Read-only enforcement and lock-wait failure tests passed. Task-owned inventory
  schemas/fixtures were removed; no development data or pre-existing Garage
  objects were changed by the inventory tests.
- Documentation file links, diff whitespace and secret exclusions were checked.

The full-suite command used the same ignored local environment file as the
focused command, then the two opt-in variables above and `.\mvnw.cmd test`.
Normal publisher/consumer/worker/reconciliation defaults remain disabled. The
temporary local AMQP tunnel changes no VM infrastructure.

## Prerequisites for B2

B2 needs an accepted expiry policy and durable recovery evidence design. It must
coordinate hierarchy -> fresh file -> allocation, revalidate exact identity,
version, age eligibility, lifecycle and ancestry, permanently preclude admission and commit the
conditional transition/evidence atomically. Tests must establish both admission
and preclusion winner orders, rollback and delayed-request rejection. Pending
folder purge remains deferred. B1 classifications cannot authorize expiry without
that new validation. Historical admitted uncertain writers remain blocked;
B3/B4 require separately accepted durable evidence and cleanup protocols.
