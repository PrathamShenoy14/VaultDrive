# Durable job worker foundation (Phase 2C)

The opt-in worker schedules existing PostgreSQL jobs and dispatches registered
`JobHandler` implementations. No file/folder purge handler is provided in this
slice. Enabling the foundation alone leaves purge jobs untouched: there is no
placeholder handler that marks them successful. Garage deletion, resource
finalization, upload reconciliation and missed-notification backfill remain planned.

## Configuration and capacity

`JOB_WORKER_ENABLED` / `vaultdrive.jobs.worker.enabled` defaults to `false`, also
in the test profile. It is independent of publisher/consumer enablement. Runtime
verification does not enable or deploy a running development API.

| Property under `vaultdrive.jobs.worker.` | Default | Purpose |
|---|---|---|
| `enabled` | `false` | Explicitly start the worker control loops |
| `concurrency` | `2` | Maximum admitted job executions per instance, allowed 1–32 |
| `poll-delay` | `5s` | Delay after each claim/dispatch iteration |
| `renewal-interval` | `10s` | Delay after each renewal/deadline scan, at most one third of the lease |
| `max-runtime` | `5m` | Monotonic local execution limit, at least the renewal interval |
| `shutdown-grace` | `5s` | One overall shutdown waiting budget, at most 60 seconds |

Durations must be positive (at least 1 ms), at most one day, and satisfy the
additional bounds above. The existing database lease remains 30 seconds, attempt
budget ten, and retry backoff one to sixty seconds. No migration is needed: renewal
updates only existing V14 claim/deadline/timestamp fields; V14/V15 are unchanged.

Two daemon control threads run fixed-delay claim and monitoring loops, with no
control task queues. Handler and renewal executors each have exactly `N` maximum
threads and an `ArrayBlockingQueue` of capacity `N`, with rejection rather than
caller-runs or thread growth. A semaphore reserves capacity before claiming, so
executing plus handed-off job claims never exceed `N`; the handler queue is only
a bounded handoff. Each claim has at most one queued/running automatic renewal.
Old renewal calls can outlive handler completion, but their executor still caps
running calls at `N` and pending calls at `N`. Saturation cancels the affected task
instead of accumulating work. Total possible worker threads are `2N + 2`.

## Scheduling and handler contract

The first poll occurs at startup, then repeats after the configured delay. Only
types with a registered handler are scanned, in round-robin order. Duplicate
handler registration for a type fails configuration. Unsupported types remain
untouched and consume no attempt budget.

For each available local slot, the poller calls the existing `claimNext(type)` in
its independent short transaction. `FOR UPDATE SKIP LOCKED`, a new token, persisted
attempt and lease govern multi-instance claims. Claim commit precedes dispatch.
Expiry recovery and abandoned final-attempt `FAILED` transitions are therefore
driven by polling for supported types, without another RabbitMQ notification.
Each claim call retains the existing 20-candidate recovery scan limit; repeated
polls continue after a call clears exhausted rows and returns empty.

Before handler invocation, the worker renews/checks the claim and rejects execution
if ownership is lost or uncertain. Orchestration must have no enclosing transaction,
and handler execution occurs after that short transaction commits. No hierarchy
or resource locks are acquired by the foundation. Future handlers must reload and
validate ownership, ancestry, business lifecycle and storage eligibility using
their own short ADR-0007 transactions and race tests. They must never hold such
transactions across external I/O.

`JobHandler.handle(JobExecutionContext)` returns explicit `SUCCEEDED` or `RETRY`.
Only a real handler's explicit success permits fenced completion. Null results
and ordinary handler exceptions record a fenced retry using existing `WORK_FAILED`
bookkeeping. Interrupted, cancelled or timed-out tasks leave their claim to expire;
late results are suppressed. An uncertain outcome-recording database operation
also leaves recovery to durable state rather than fabricating a second outcome.
Fatal handler errors are sanitized and abandoned without raw exception logging.

Handlers must call `context.checkActive()` between bounded work units, propagate
interruption and bound their own external I/O. `context.claim()` is an immutable
identity/token snapshot; its original expiration does not change after renewal.
`isActive()` describes conservative local cancellation/deadline state, not a
guarantee of exclusive external execution.

## Lease renewal and cancellation

`BackgroundJobService.renew(claim)` takes only the identified job row lock, reads
protected state after acquiring it, and requires `RUNNING`, the exact token and an
unexpired lease using PostgreSQL's clock. It then conditionally updates again with
the same fencing predicate. Expiration becomes at least database-now plus 30
seconds, never shorter than the existing lease. `next_attempt_at` moves to the
renewed expiration plus the persisted attempt's backoff. Attempts, token and
lifecycle state do not change. Expired or replaced claims cannot be revived.

The monitor dispatches renewal database calls through its bounded executor so a
blocked renewal cannot block deadline checks for every task. Failed, rejected or
uncertain renewal cancels local work and requests interruption. Cancellation is
irreversible and never frees a worker slot before the actual handler returns.
An interrupt-ignoring handler can reduce available capacity, but cannot cause
unbounded replacement-thread growth.

A monotonic local deadline conservatively counts 30 seconds from *before* each
successfully confirmed renewal call, not from its return. This includes database
call/commit delays in the local safety margin and makes a stalled renewal locally
expire even while the five-minute runtime limit has not elapsed. Out-of-order
renewal responses never move that local deadline backward. The max-runtime limit
still caps total execution despite successful renewals. Deadline detection requests
interruption on the next monitor iteration; context checks and result recording
also reject elapsed deadlines.

PostgreSQL remains authoritative. Local deadlines cannot account for every server
clock jump, JVM pause or external I/O stall, and token fencing cannot cancel external
effects. Expired workers can overlap newer attempts outside PostgreSQL; future
handlers still require idempotence and storage/upload writer fencing.

## Shutdown and crash recovery

The Spring context-close event stops admission immediately without waiting for
database calls. Destruction then allows active handlers to finish within one
shutdown grace budget, with renewal continuing during that grace window. At its
end, monitoring stops, unfinished contexts are cancelled, and executors/control
threads are interrupted. Waiting is bounded across all these resources by the
same deadline. No new grace period is added for each executor.

Queued/unfinished claims remain durable. Cancellation never changes them to
`SUCCEEDED` or immediately releases ownership. A claim transaction already in
flight can commit after admission stops; it is not dispatched and its lease is
left recoverable. A renewal already in flight may still commit once after shutdown,
so recovery uses its final persisted lease/deadline. An outcome transaction begun
before cancellation may commit its legitimately finished result.

Interrupting Java threads cannot forcibly terminate an unresponsive handler or
database driver. Such daemon threads may remain until they return or the process
exits; shutdown bounds waiting, not absolute resource termination or external I/O.
No replacement handler threads are created to bypass stuck capacity.

| Failure point | Durable state and recovery |
|---|---|
| before claim commit | transaction rollback leaves job eligible without a committed attempt |
| after claim commit, before dispatch | lease/attempt remain; later supported-type polling reclaims when due |
| handler/renewal interruption, timeout or shutdown | stop local work/renewal; recover after final lease and retry deadline |
| known handler failure | fenced `PENDING` retry or `FAILED` at the attempt limit |
| external effect then lost outcome commit | retry may repeat the effect; idempotence is required |
| stale result/renewal after replacement | old token rejected; newer persisted state is preserved |
| abandoned final attempt | later due scan records durable `FAILED`, with no automatic reset |
| no registered handler | no claim, attempt, renewal or success; wait for a real implementation |

Jobs missing because notifications never created them still need future outbox-to-job
recovery/backfill. Consumer outage restart policy, quarantine disposition and job
identity/state machine remain as documented in [consumer recovery](purge-consumer.md)
and [background jobs](background-jobs.md). Retention/reset tooling remains absent.

## Verification

`BackgroundJobIntegrationTest` adds PostgreSQL renewal/deadline tests, including
expired/replaced-token rejection and expiry during a row-lock wait.
`JobWorkerIntegrationTest` uses isolated `job_worker_test` fixtures and bounded
latch-coordinated handlers to exercise concurrent schedulers, execution outside
transactions, automatic renewal, crash/timeout recovery, capacity reservation,
slow renewal isolation and shutdown. `JobWorkerConfigurationTest` checks disabled
startup, absent/duplicate handlers, timing/capacity validation and actual Spring
context closure. No test handler is registered in production code.

Load the ignored local environment before running Maven. Run focused tests first:

```powershell
cd backend
. .\.env.local.ps1
.\mvnw.cmd "-Dtest=BackgroundJobIntegrationTest,JobWorkerConfigurationTest,JobWorkerIntegrationTest" test
.\mvnw.cmd test
```

Full verification also uses the existing isolated-vhost broker test setup. Test
profile pools are capped at five connections with zero minimum idle, so cached
Spring contexts do not exhaust the development PostgreSQL connection limit.
Production pool configuration and VM infrastructure are unchanged. Test results
belong in task reports. No real process-kill, production load, broker
failover or physical purge validation is claimed by this foundation.
