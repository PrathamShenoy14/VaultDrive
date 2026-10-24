# MVP operational hardening

Frontend M3 consumes these unchanged controls through a narrow authenticated BFF.
Its [bounded transfer/recovery UX](frontend-m3-transfers.md) polls status without
automatic control mutation retries and preserves unknown outcomes. Native file
downloads stream through Next.js with request cancellation and separate header/
stream deadlines; Chrome owns transfer interruption/completion. No processor,
VM, Garage CORS, DNS, certificate, port or firewall configuration is changed.

## API freeze integration boundary

The [freeze](api-contract-freeze.md) and [frontend guide](frontend-integration.md)
require a same-origin trusted HTTPS `/api/v1/**` proxy preserving auth/CSRF,
idempotency/precondition and status/retry/download headers, without mutation
retries or personalized caching. The backend has no credentialed cross-origin API
policy; Garage PUT CORS remains separate. OpenAPI is a committed [test-generated
artifact](openapi.json), not a public HTTP surface. 406 uses the common sanitized
error envelope and authorizes no storage retry. The [real-browser gate](browser-multipart-verification-2026-10-23.md),
physical-capacity/retention and other launch requirements below remain open.

## Zero-byte creation recovery

POST `/api/v1/files/empty` uses bounded metadata admission and the existing
single-attempt SDK/storage deadlines. Save the returned Location and idempotency
key. GET `/api/v1/empty-file-creations/{id}` inspects retained status; POST its
`/recover` suffix can verify the original bound object or finalize an existing
receipt. It never repeats PUT. Reusing the original creation request/key may
dispatch only a still-RESERVED intent. An UNKNOWN result, timeout or lost response
does not authorize another write with that key. A new key means a different file
and can collide with the retained name.

STORAGE_COMPLETE retains a positive receipt but cannot publish READY through
Trash, hidden copy staging or purge barriers. Restore eligibility when permitted,
then recover. An owner can recover Editor-created files; the original actor/name
is preserved. Revoked Editors cannot inspect or recover the intent. Existing
internal receipt finalization can finish eligible empty files without Garage I/O.
An admitted UNKNOWN empty write retains the conservative SERVER_PUT folder-purge
blocker; never mark it never-written or deleted because its logical length is
zero. No autonomous unknown-write scanner or dispatch takeover is added.

Empty objects use real unique Garage keys and normal downloads/copies/deletion.
Keep exact binding, dispatch, receipt, allocation and deletion evidence. DELETE
receipts establish the existing exact-key outcome, not block GC. Zero logical
charge does not mean zero physical overhead or unlimited physical capacity. Existing
admission, capacity monitoring and retention launch gates remain. See
[ADR-0016](decisions/0016-zero-byte-files.md).

This milestone bounds API and background work and makes failures visible. It does
not establish production capacity, browser release, backup restoration, physical
multipart reclamation or safe resolution of unknown storage effects.

## Admission and retry contract

Every limit is **per JVM**, fixed memory, with immediate rejection and no wait
queue. Tokens refill on monotonic time; restarting refills the finite burst.
Neither IPs nor user IDs allocate limiter entries. Authentication is limited
before BCrypt/refresh processing; all four lanes run before bearer database checks.

| Lane under `vaultdrive.admission.` | Routes | Concurrency | Burst | Tokens/second |
|---|---|---:|---:|---:|
| `authentication` | `/api/v1/auth/**`, including refresh/logout | 4 | 10 | 5 |
| `multipart` | All `/api/v1/upload-sessions` control requests, including creation/signing/resume/completion | 8 | 40 | 20 |
| `downloads` | `/api/v1/files/{id}/download` | 4 | 20 | 10 |
| `metadata` | Other authenticated API routes, including search, listings/history, usage, notifications and expensive copy admission/execution | 16 | 100 | 50 |

Each lane configures `concurrency` (1..128), `burst` and `per-second` (1..10,000).
Health is exempt. Public links retain their existing separate request bucket,
request semaphore and full synchronous download semaphore. All rejections use the
uppercase ApiError status 429, `Cache-Control: no-store`, `Retry-After: 1`; the
authenticated message is `Request capacity exceeded`. 429 can precede a 401 or
resource authorization because no resource lookup has occurred. Retry-After is a
minimum client delay, not a reservation or promise of capacity. Add jitter.

Retry only a **received rejection**, using the original idempotency key when
applicable. A lost response, network failure, 202 or 503 does not prove a mutation
was rejected. Read retained status and follow the existing recovery protocol.
Never blindly retry refresh rotation or uncertain Create/Complete/Abort/Copy.
Existing signed part capabilities remain valid until their own deadline.

Request permits close on synchronous errors. Download permits span opening storage
and actual stream exit. Async completion/timeout/error also closes streams whose
callback never starts; cancellation of a running callback retains capacity until
it exits. Partial S3 streams abort rather than drain. MVC uses a fixed executor
with `downloads.concurrency + 1` maximum threads, zero queued tasks and a five-minute
async timeout. The extra thread bounds handoff overlap, not admitted transfers.
Public streams remain synchronous. Socket/output interruption is not a forcible
thread-termination guarantee; a stuck stream holds capacity. The edge must bound
client idle time and request bodies/connections.

These limits provide aggregate admission, not tenant fairness or brute-force/DDOS
protection. A trusted same-origin HTTPS edge with per-client authentication limits,
fleet-wide connection/rate bounds and appropriately redacted logs is a launch
prerequisite. Multiplying replicas multiplies these process limits. No throughput
or latency improvement is claimed; capacity/load tuning remains unmeasured.

## Deployment configuration and startup

Review [the sanitized deployment example](../backend/deployment.example.properties)
and supply credentials through private environment/configuration. Activate
`deployment` explicitly; merely setting individual worker flags locally does not
assert deployment readiness. Do not start this profile against shared data while
following the test procedures below.

The deployment profile requires these flags **all explicitly true**:

| Switch | Responsibility |
|---|---|
| `OUTBOX_PUBLISHER_ENABLED` | Confirmed, mandatory publication of committed sources |
| `PURGE_CONSUMER_ENABLED` | Commit-before-ACK durable purge-job ingestion |
| `SHARING_NOTIFICATIONS_CONSUMER_ENABLED` | Sharing and quota recipient ingestion |
| `JOB_WORKER_ENABLED` | Existing bounded file/folder purge execution and lease recovery |
| `MISSING_JOB_RECONCILIATION_ENABLED` | Existing aged PUBLISHED/unquarantined matching-intent recovery |
| `PURGE_FINALIZATION_ENABLED` | Database-only retained SUCCEEDED standalone-file finalization |
| `MULTIPART_EXPIRY_ENABLED` | Database-only logical abandonment of expired sessions |

`vaultdrive.operations.probes-enabled=true` is also required and is set by the
deployment profile. The local equivalent is `OPERATIONS_PROBES_ENABLED`; all
processors/probes remain disabled in the base and test profiles. The operator
profile forces them off independently of ordinary environment switches.

Startup validation runs before any runtime bean starts. It rejects missing
processors/credentials/identities, OSIV=true, disabled Hibernate validation,
Spring automatic broker declarations, incorrect confirm/return settings, template retries,
non-HTTPS/credential-bearing storage endpoints and processor delays at or above
the readiness freshness bound. Timing/capacity records also reject invalid ranges.
It cannot validate certificate trust, browser reachability, credentials' privileges,
edge policy or a running old writer merely from configuration.

Deployment defaults bound Tomcat to 64 threads, 256 connections and accept queue
32, with five-second connection timeout. Hikari uses 16 maximum connections,
three-second acquisition/connect, two-second validation, 15-second socket timeout,
10-second PostgreSQL statement and two-second lock-wait limits. Specific shorter
transaction/protocol budgets remain. Graceful server shutdown has a ten-second
phase budget; existing worker shutdown/cancellation/lease rules still apply.
These are component bounds, not a global wall-clock shutdown or operation bound.

Before deployment:

1. Retire old API/worker/permission consumer binaries and external uncoordinated
   writers. Back up PostgreSQL and Garage using an approved, restore-tested plan.
   Migrate V1..V46 without rewriting applied migrations; preserve all original
   bindings, allocations, manifests, receipts, outbox, job and quarantine evidence.
   PostgreSQL needs `pg_trgm` in public and sufficient migration privileges.
2. Provision/verify the already agreed durable direct exchange, both quorum queues
   and bindings before consumers start. Use the configured purge and permission
   routes from [publisher operations](outbox-publishing.md); the application adds
   no DLX or alternate broker. Use approved private credentials/vhost and network
   encryption/isolation. Do not enable rapid restart loops for stopped consumers.
3. Provide a trusted browser/backend-reachable HTTPS Garage binding, exact-origin
   CORS, same-origin secure-cookie API edge and monitored clock synchronization.
   Existing immutable endpoints cannot be rewritten to the new URL. Historical
   binding support needs its own reviewed policy.
4. Review all bounded limits against resource budgets. Enable processors together
   only after the intended database/topology and storage identity are verified.
   Route traffic only after readiness becomes UP; first successful recovery polls
   take about one minute with defaults.

No VM, bucket, broker policy, shared schema or service credential is changed by
this milestone's implementation or normal verification.

## Bounded background processing

Expiry configures `vaultdrive.multipart.expiry.page-size` (20, range 1..100) and
`poll-delay` (1m, range 1s..1h), also available through
`MULTIPART_EXPIRY_PAGE_SIZE` / `MULTIPART_EXPIRY_POLL_DELAY`.
One daemon thread processes one keyset page per delay, initially waiting one delay.
Each candidate separately revalidates PostgreSQL expiry/decision/lifecycle under
the existing hierarchy -> file -> allocation -> session -> cancellation order.
Transaction/statement/lock budgets are 10/5/2 seconds. It records abandonment and
eligible FAILED/name/quota release. It never grants or sends Abort, deletes bytes,
releases an immutable allocation or claims physical reclamation. Blocked rows are
revisited after cursor wrap; scan failure retains the cursor. Restart starts at
the first page, and the existing decision guards make duplicates harmless.

Finalization configures `vaultdrive.jobs.finalization.page-size` (20, range 1..100)
and `poll-delay` (1m, range 1s..1h), with corresponding
`PURGE_FINALIZATION_PAGE_SIZE` / `PURGE_FINALIZATION_POLL_DELAY`. Its separate daemon
processes one existing `FilePurgeRecoveryService.recoverPage` per tick. Only retained
SUCCEEDED file jobs with matching target/allocation/proof can finalize; no Garage
I/O, job reset or source reconstruction occurs. Existing resource -> work -> final
job-fence ordering remains. Scans have a five-second query bound. Blocked receipts
are retained and revisited; stopped contexts interrupt further scheduled admission.

Existing publisher, worker and missing-job bounds remain in
[publication](outbox-publishing.md), [workers](job-workers.md), and
[reconciliation](missing-job-reconciliation.md). Consumers remain one thread,
prefetch one, three transactional attempts per delivery and explicit stop on
exhaustion or uncertain ACK. Restart/redelivery preserves the original job and
notification UUIDs and read state. There is no automatic stopped-consumer restart.
No page size creates an absolute whole-page deadline; sustained arrival/blocked
work can delay cursor wrap. Tune only after measurement.

## Health, monitoring and alerts

`GET /api/v1/health` remains public process liveness. It does not query dependencies.
`GET /api/v1/health/ready` is public and returns 200 UP or 503 DOWN plus no-store,
using only a cached snapshot; it exposes no dependency names, counts or credentials.
It starts DOWN, stays DOWN when probes are disabled, fails closed when stale and
becomes DOWN immediately on context closure.
Readiness should remove traffic, not trigger destructive restarts. Retained business
recovery states remain alerts so the API needed to recover them stays reachable.

The probe owns one daemon thread, no queue, running every 15 seconds. Database
inspection commits before Garage HeadBucket and Rabbit passive queue checks.
Garage uses existing one-attempt SDK, ten-/five-second call/attempt budgets;
Rabbit uses existing connect/RPC bounds. Each database statement/lock is bounded
to three/one seconds, inside a ten-second transaction. Counts stop at 101:
**101 means 101 or more**, not an exact total. A failed/slow probe cannot add probe
threads; a snapshot older than `stale-after` (2m, range 15s..10m) fails closed.
Probe delay is 5s..1m and freshness must be at least twice that delay. No network
I/O is permitted from an enclosing transaction.

Sanitized `Operational readiness` logs contain fixed component names, booleans,
capped counts and per-lane active/rejected totals. Never enable credential-bearing
AMQP logs, SQL bind logging, request bodies or signed/header capability logging.
Scrape/ship these logs using deployment infrastructure; no new monitoring service
is introduced. Rejection totals reset on JVM restart.

| Signal / alert condition | Readiness / response |
|---|---|
| PostgreSQL unavailable/inspection timeout; Garage HeadBucket denied/unavailable; Rabbit unavailable or either queue missing | DOWN; fix the specific dependency, do not infer object absence |
| Either local required container stopped/no active consumer, or broker queue reports no consumer | DOWN; inspect failure and explicitly restart once corrected |
| Publisher/reconciliation/expiry/finalization has no successful poll within 2m; worker loops dead/stale or cancelled execution still occupies a slot | DOWN; inspect logs/claims before restart |
| Failed publications without a successful audited replay, exhausted jobs, unresolved quarantine | DOWN immediately; exact operator investigation |
| Pending publication/job past due for 5m, PUBLISHED purge source without job or expected recipient notification for 5m, unfinished SUCCEEDED finalization or undecided expired session overdue 5m | DOWN; inspect backlog/consumer/reconciler/receipts/expiry |
| INITIATING, completion/cancellation/copy recovery blocked/uncertain for 30m | Alert only; client action may be required, so keep recovery APIs reachable and retain evidence for positive-only recovery |
| `unresolvedExpiry` > 0 | Alert only; logical expiry does not prove storage cleanup; retain identities/effects |
| Sustained lane rejections, active slots at cap, growing backlog or repeated timeout | Capacity/abuse alert; inspect and measure before changing limits |

`backlog-age` is configurable 1m..1d (default 5m), `recovery-age` 1m..1d (30m).
Pending recovery counts are bounded candidate observations and can overlap.
Quarantine remains retained after replay; its signal excludes event IDs with a
successful audited replay. Failed publication similarly remains FAILED: direct
ingestion does not claim a broker confirm. Malformed quarantine without an
authoritative event cannot be reconstructed; its disposition needs a separately
reviewed incident decision. Dependency probes are availability evidence, not a
continuous guarantee of successful UploadPart, durable replicated bytes or GC.
Expected recipient checks include grant recipients and quota owners, exclude
non-notifying link events, and detect stalled notification ingestion even while a
consumer still reports active. They observe only; no notification replay is scheduled.

## Safe operator inspection and replay

Use a private workstation/process with the intended database credentials. Stop
normal consumers/workers while diagnosing an incident; do not change shared data
without the operational approval appropriate to that deployment. An operator
command is an explicit mutation, not something to run during routine inspection.
The profile disables migrations, HTTP and **every** autonomous component, even if
ordinary environment flags are true. Startup fails if those are overridden on.
It also rejects migration enablement, schema creation/update and Spring automatic
broker declaration overrides before runtime beans initialize.
It requires an already migrated V45 database. Nested normal ingestion transactions
retain operator-connection ten-second statement/two-second lock budgets and a
15-second socket timeout. Build the jar after verification.

```powershell
cd backend
. .\.env.local.ps1
# Supply the exact reviewed database/schema through SPRING_DATASOURCE_URL privately.
java -jar target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=operator --operator.command=inspect
```

Inspection prints only capped work counts. Privately inspect at most 100 rows per
page, retaining the previous UUID as `:after`; bind values using approved SQL tooling:

```sql
BEGIN READ ONLY;
SET LOCAL statement_timeout='3s';
SET LOCAL lock_timeout='1s';
SELECT id,event_type,delivery_status,attempt_count,next_attempt_at,published_at,last_failure_code
FROM outbox_events WHERE (:after::uuid IS NULL OR id>:after) AND delivery_status IN ('FAILED','PUBLISHED')
ORDER BY id LIMIT 100;
SELECT id,status,attempt_count,failure_count,max_attempts,progress_version,
       claim_expires_at,next_attempt_at,last_failure_code
FROM background_jobs WHERE id=:event_id;
SELECT body_sha256,event_id,reason_code,created_at FROM notification_quarantine
WHERE event_id=:event_id ORDER BY created_at LIMIT 100;
SELECT id,action,incident,outcome,prior_attempts,prior_failures,prior_budget,new_budget,created_at
FROM operator_actions WHERE target_id=:event_id ORDER BY created_at,id LIMIT 100;
COMMIT;
```

For stopped consumers: inspect queue backlog and controlled stop reason; restore
database/broker availability or correct unsupported/mismatched delivery first.
Explicitly restart the application/container once. A channel close/redelivery or
commit-before-ACK loss must deduplicate; do not reset jobs or notifications.
Use [the ingestion runbook](purge-consumer.md) and [sharing delivery](sharing-m6.md).

For broker loss after publication, quarantined known sources after code correction,
or exhausted publication, ingest the **original retained authoritative event**:

```powershell
java -jar target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=operator --operator.command=replay-event --operator.incident=INC-123 --operator.ids=<canonical-event-uuid>
```

`operator.ids` accepts 1..20 comma-separated distinct canonical UUIDs, validated
before mutation; each target commits separately. No raw payload argument exists.
Only retained PUBLISHED/FAILED sources are accepted. The original envelope passes
the normal strict typed parser and committed-source checks, then idempotent job or
notification ingestion. No Rabbit publication, Garage call or lifecycle change
occurs. Original event UUID/timestamp/payload, publication counters/status,
quarantine and notification read state remain. A successful action means ingestion
committed, not that purge completed. REQUESTED/UNKNOWN audit means inspect durable
state; duplicate replay is safe and never resets an existing job.

Missing purge jobs without quarantine may instead use the existing independently
bounded missing-job reconciler after its five-minute eligibility delay and exact
intent/target/run validation. Quarantined sources require the explicit replay above;
the scanner never clears them. Historical SUCCEEDED-file metadata recovery uses
the new finalization poller; its retained proof remains authoritative.

For an exhausted job, diagnose/correct its cause and review the immutable target,
manifest, receipts and last failure before granting another small tranche:

```powershell
java -jar target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=operator --operator.command=extend-job --operator.incident=INC-124 --operator.ids=<canonical-job-uuid> --operator.additional-failures=1
```

Each exact FAILED/exhausted job can receive 1..10 additional failures, up to total
budget 1,000. One short job-only transaction locks then freshly reads the job,
appends immutable incident/prior-counter/budget audit, and changes it to PENDING
with the larger maximum. Attempts, failures, checkpoints, type/aggregate, receipts
and last failure remain; no live token is stolen. Concurrent operator calls yield
one winner per exhaustion. Old-token completion remains rejected. A later worker
must revalidate all normal resource/proof/storage safeguards. This cannot repair
unknown multipart/copy effects or missing historical bindings. Repeated extensions
are explicit incident decisions, never a scheduler. Do not reset counters, disable
triggers, manufacture another job or mark work successful manually.

## Remaining launch gates

Physical multipart reclamation, unknown initiation/completion/abort/copy outcomes,
late effects and historical missing evidence cannot safely be automated here.
Logical quota release on cancellation/expiry does not cap retained parts or late
objects. Repeated abandonment can grow physical usage despite logical quota
enforcement; launch needs an explicit physical-capacity monitoring/admission and
retention policy. Request rate bounds alone do not resolve this exposure.
No negative HEAD/ListParts result, 204 abort, elapsed URL/session deadline or logical
folder FINALIZED grants redispatch/removal authority. Retain those allocations and
review [multipart recovery](multipart-completion-recovery.md),
[cancellation](multipart-cancellation-expiry.md), [copy recovery](file-copy.md) and
[historical cleanup restrictions](known-success-upload-cleanup.md).

Live HTTPS/browser/CORS/clock verification, restore-tested backup/disaster recovery,
credential/key rotation, trusted-writer retirement, edge abuse protection, capacity
measurement and an explicit physical-capacity/retention budget remain launch
prerequisites. Malformed source-less quarantine, missing immutable identities or
irreconcilable proof need incident-specific decisions. No readiness boolean closes
these gates. Frontend remains outside this milestone; the later zero-byte extension
is described above and does not close any deployment gate.

## Verification

Use fresh PostgreSQL schemas, the existing isolated `vaultdrive_outbox_test` vhost,
the documented localhost-only SSH tunnel and disposable Garage keys/journals from
[the continuous purge runbook](async-purge-verification.md). Do not repair V26's
historical shared-schema checksum drift. Default HTTP test fixtures raise only
token rates/bursts; dedicated admission tests use constrained real limits.

Focused new tests cover token refill, concurrent requests, all permit-release paths,
startup-before-runtime validation, stale/failed readiness, expiry/finalization cursor
restart, replay validation/idempotence and audited budget rollback/concurrency.
Existing PostgreSQL races, worker restart/lease/duplicate checks, stopped/redelivered
broker consumers and Garage uncertainty tests remain required regressions.
Executed totals and gated skips are recorded in [architecture evidence](architecture.md#mvp-operational-hardening-verification).
