# Missing-job reconciliation (Phase 2E.4)

An opt-in PostgreSQL poller recovers the narrow gap where publication is committed
but notification ingestion never committed a durable job. It creates PENDING jobs
through the existing BackgroundJobService, without broker or Garage I/O. It changes
no outbox delivery state, resource intent, target, run, receipt or existing job.
See [ADR-0008](decisions/0008-transactional-outbox-rabbitmq.md#phase-2e4-bounded-missing-job-reconciliation)
and [locking](decisions/0007-concurrency-locking.md#phase-2e4-missing-job-ingestion).

## Eligibility and trust

The bounded candidate scan selects committed PUBLISHED rows whose published_at is
at least eligibility-delay old, with no background_jobs row of the same UUID.
Ingestion rechecks publication age and excludes any notification_quarantine row
with that event_id. A quarantine entry without an identifiable event UUID cannot
be associated with a source; it cannot suppress all unrelated recovery.

The original event UUID/type/version/aggregate/owner/resource/createdAt are parsed
through the same strict v1 notification parser and matched against the committed
outbox by NotificationIngestor. JSON value types are retained during parsing;
unsupported, malformed, contradictory and oversized sources create no job.
Only FILE_PURGE_REQUESTED/FILE/fileId and FOLDER_PURGE_REQUESTED/FOLDER/folderId
are supported. Payload materialization is capped at 60,000 bytes before constructing
the envelope, which also passes the consumer's 65,536-byte body limit.

Further conservative validation requires the fresh resource and retained intent:

- File: same event/file/owner/request timestamp in V16 target; matching permanent
  allocation namespace/endpoint/region/bucket/key and admitted write; matching
  current READY file owner/key/version with deleted_at and purge_requested_at.
- Folder: existing V19 run with the original event/root/owner/request timestamp,
  matching current explicitly trashed root and its purge_requested_at.

No target, run, allocation, binding, byte receipt or missing metadata proof is
manufactured. Configuration is never used to reconstruct a storage key. Events
predating the retained targets/runs, missing current resources, unknown bindings,
quarantined sources and pre-outbox intents remain blocked for separate review.
Folder descendants are still validated by the existing bounded execution workflow;
job creation does not certify writer safety or successful deletion.

## Configuration and bounds

| Property under vaultdrive.jobs.reconciliation. | Default | Bound / purpose |
|---|---|---|
| enabled | false | MISSING_JOB_RECONCILIATION_ENABLED; explicit opt-in |
| eligibility-delay | 5m | MISSING_JOB_RECONCILIATION_DELAY; 1m..7d since publication |
| poll-delay | 1m | 1s..1d after each page, also the initial startup delay |
| page-size | 20 | 1..100 events per invocation |

Publisher, consumer and worker flags remain independently disabled by default.
Enabling reconciliation alone creates jobs but performs no physical deletion.
If a worker is already explicitly enabled, recovered jobs become executable by it.
This implementation is not enabled or deployed to a running development API.

V23 adds a partial published_at/id index without rewriting existing rows. Scans
use (published_at, id) keyset pagination, a LIMIT and an indexed job anti-join;
there is no OFFSET or full outbox materialization. Each scan and each ingestion
uses its own ten-second Spring transaction timeout, five-second PostgreSQL
statement_timeout and two-second lock_timeout. These are component bounds, not
a whole-page wall-clock deadline: up to 100 independent event transactions may run.
Pool acquisition, driver/network failure and shutdown termination are not absolute
wall-clock guarantees. No throughput or production-load claim has been measured.

One daemon fixed-delay task per enabled instance keeps only a cursor and one page
of scalar candidates. No per-event executor queue is created. Ingestion takes the
shared owner hierarchy lock, then freshly reads/locks the resource FOR SHARE,
validates retained intent, and finally inserts/finds the job identity. It acquires
no resource after the job insert. Existing worker lock order/fences are unchanged.
Quarantine is checked at the ingestion read; this does not fence a later concurrent
quarantine insertion or change the consumer's valid-replay policy.

Empty pages wrap to the beginning on the next poll. Skipped/failed events advance
the cursor so they do not pin later pages; a later sweep retries them. A failed
scan retains its cursor. A restart starts a new sweep; a durable recovery cursor
is unnecessary for correctness because insertion is idempotent. Continual growth
can delay wraparound; fairness and production tuning are unmeasured. Shutdown
interrupts the daemon and stops scheduling; an in-flight transaction may finish.

## Deduplication and counters

BackgroundJobService.createIfAbsent uses the same ON CONFLICT (id) DO NOTHING and
type/aggregate identity check as ordinary consumer creation. The insert result
distinguishes actual creation from a concurrent winner, after transaction commit.
Competing consumers/reconcilers converge on one original-UUID job. Existing PENDING,
RUNNING, SUCCEEDED and FAILED rows are excluded by scans and never reset by duplicate
creation. Attempts, leases, checkpoints, failure budgets, receipts and terminal
timestamps are preserved. Identity conflict rolls back; no replacement occurs.

Each page returns and each scheduled poll logs bounded counters with no labels:

- scanned: event transactions attempted, at most page-size.
- created: ingestion returned after a successful new-job commit.
- alreadyPresent: an identical job won the race after candidate scanning.
- skipped: source disappeared/became ineligible, quarantine, invalid source or
  missing/inconsistent retained intent. The event remains retained.
- failed: event transaction/identity/commit failure, including timeout or unknown
  commit outcome. A later sweep safely discovers any committed job.
- scanFailed: separate boolean for scan failure; no event is counted or cursor advanced.

scanned = created + alreadyPresent + skipped + failed. These are per-page
operational counters, not durable audit totals or deletion counts. No exception
text, raw payload, key, endpoint, owner, token or credential enters these logs.

## Operator recovery

The [operational hardening runbook](operational-hardening.md) now requires this
poller in the deployment profile, observes its freshness/backlog and supplies
audited exact-source replay for reviewed quarantine or exhausted publication.
The scanner's conservative age/intent/quarantine gates below remain unchanged;
it still never rearms terminal work or performs storage I/O.

Keep workers disabled while investigating the missing-job gap. Privately inspect
the intended database, outbox publication age, same-UUID job and quarantine. Enable
only MISSING_JOB_RECONCILIATION_ENABLED in private configuration after reviewing
the intended database; use the five-minute default unless a measured delivery
delay justifies another allowed value. The first poll waits poll-delay.

Observe counters over complete sweeps. A created job means durable work accepted,
not deletion completed. Inspect execution through [background jobs](background-jobs.md)
and [workers](job-workers.md). Do not republish/reset events or jobs to make counters
look healthy. Disable reconciliation to stop further sweeps; existing jobs remain.

For skipped rows, use bounded private keyset queries over PUBLISHED event UUIDs
without jobs, then inspect a reviewed event's quarantine, original outbox identity,
resource intent and V16 target/allocation or V19 run. Retain all evidence. Diagnose
missing/changed historical data; do not fill storage bindings from today's config,
delete quarantine automatically, reset terminal jobs or infer deletion from absent
metadata. Consumer replay after an explicit correction retains its existing policy;
this slice provides no repair/replay API or quarantine disposition tool.

For failed/scanFailed pages, restore database availability or resolve contention
before reducing polling delay. Each later poll has bounded work; no immediate
retry loop runs. Preserve counters and failed sources for investigation. Terminal
job recovery, legacy backfill, stale uploads/orphans, retention and storage-side
writer fencing remain separate decisions.

## Verification

From backend/, load the ignored environment in the same shell as Maven:

```powershell
. .\.env.local.ps1
.\mvnw.cmd '-Dtest=MissingJobReconciliationIntegrationTest,MissingJobReconciliationConfigurationTest,NotificationIngestionIntegrationTest,BackgroundJobIntegrationTest' test
# Existing isolated test vhost and localhost tunnel; never enable development processing.
$env:RABBITMQ_TEST_VHOST = 'vaultdrive_outbox_test'
$env:ASYNC_E2E_TEST = 'true'
.\mvnw.cmd test
```

The new integration suite uses only missing_job_test in vaultdrive_test, real
PostgreSQL and disposable owner/resource/event identities. It covers both consumer
race winner orders, repeats/terminal states, unpublished/recent/uncommitted sources,
corrupt/unsupported/missing identities/proofs, quarantine, keyset pages, lock and
statement timeouts, disabled startup and unchanged Garage bytes. Only the explicit
Garage control test allocates a real object, reads it after reconciliation and
cleans that disposable allocation. No worker is enabled. Existing live broker and
continuous pipeline checks retain [their isolation procedure](async-purge-verification.md).
Execution totals belong in the task report; skipped tests are not verification.

Verification on 2026-10-16: the final focused run passed 82 tests; the final full
backend suite passed 711 tests with zero failures, errors or skips. This includes
39 new recovery/configuration cases, all seven live RabbitMQ tests and the real
HTTP-to-Garage continuous harness. Disposable Garage bytes remained readable
after missing-job creation; pipeline-owned schema/topology/object cleanup passed.
Diff/Markdown-link and private-value exclusions passed. All autonomous defaults
remain disabled; no development deployment, infrastructure/credential change,
pre-outbox repair or production-load/failover guarantee is claimed.
