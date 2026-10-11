# Durable background jobs (Phase 2A)

`background_jobs` is the authoritative execution record. `outbox_events` records
notification delivery: `PUBLISHED` means broker acceptance, never completed work.
V14 and `com.vaultdrive.job` add persistence only. No API creates jobs yet, and no
consumer, execution worker, recovery scheduler, Garage deletion, or reconciliation
is wired in this phase. Existing purge and outbox behavior is unchanged.

## Identity and creation

`BackgroundJobService.create(eventId, type, aggregateId)` uses the stable outbox
event UUID as the job primary key. One job is allowed per event, including after
success or failure. Concurrent inserts use `ON CONFLICT DO NOTHING`; a duplicate
returns the existing ID without resetting attempts, leases, or state. Reusing an
ID with a different type or aggregate is rejected. Distinct events for the same
aggregate are allowed; future consumers must validate intent against business
state. This persistence layer is not an authorization boundary.

Creation joins the caller transaction, or starts one if absent. Future consumers
must acknowledge only after that transaction commits. Rollback leaves no job.
Neither aggregate nor event has a foreign key: jobs must survive business metadata
and outbox retention. Terminal retention must preserve deduplication identity;
deleting a job would allow replay to recreate work. No retention/reset tooling exists.

Only `FILE_PURGE`/`FILE` and `FOLDER_PURGE`/`FOLDER` are supported. Jobs store no
payload, names, object keys, credentials, or exception text. Future workers must
reload ownership, ancestry, aggregate state, and lifecycle eligibility.

## State machine

| Current state | Trigger | Result |
|---|---|---|
| absent | create with stable event ID | `PENDING`, zero attempts |
| `PENDING`, due | claim | `RUNNING`, increment attempt, fresh token/lease |
| `RUNNING`, live matching token | complete | `SUCCEEDED`, terminal timestamp, clear claim |
| `RUNNING`, live matching token | retry below budget | `PENDING`, delayed retry, clear claim |
| `RUNNING`, live matching token | retry at budget | `FAILED`, terminal timestamp, clear claim |
| `RUNNING`, expired lease and due retry | claim below budget | `RUNNING`, new token, increment attempt |
| `RUNNING`, expired lease and due retry | scan at budget | `FAILED`, terminal timestamp, clear claim |
| any | identical duplicate create | unchanged |

`SUCCEEDED` and `FAILED` are terminal. Completion/retry with a stale token or expired
lease returns false without changing state. No automatic reset exists. Only
`WORK_FAILED` and `LEASE_EXPIRED` failure codes are stored. Future completion callers
must establish the work outcome first; this foundation cannot prove deletion.

## Claims and recovery

`claimNext(type)` uses an independent short JPA transaction, locking only job rows
ordered by retry time, creation time, and ID. Protected state is read through
`FOR UPDATE SKIP LOCKED`. Competing workers skip locked candidates and cannot
simultaneously acquire the same live claim. No hierarchy/resource locks are acquired.
Completion/retry lock the identified job row, check ownership using PostgreSQL's
clock after acquiring the lock, then conditionally update by status, token, and
unexpired lease. Retry uses persisted attempts, not caller snapshot metadata.

Claims commit before returning an immutable snapshot. Future orchestration must
run outside an enclosing transaction: claim, external I/O, and outcome recording
are separate steps. `REQUIRES_NEW` suspends an outer transaction; it does not close
it. Callers must hold no outer transaction/hierarchy lock across I/O. Resource
mutations will need their own ADR-0007 lock order and race tests. This foundation
never reads or locks file/folder rows.

Policy is fixed for this slice: ten total attempts persisted per job, a 30-second
lease, and `min(2^(attempt-1), 60)` seconds of backoff. Known failures schedule from
database time at failure recording. Each claim persists its recovery deadline as
lease expiration plus backoff; abandoned attempts count toward the limit. Both
lease and retry deadline must pass before reclaiming. PostgreSQL's clock governs
deadlines, avoiding worker-host skew. No renewal API exists; future workers must
use bounded units fitting the lease or explicitly design renewal.

Recovery is part of `claimNext`: each call examines at most 20 eligible rows,
marks exhausted claims failed, and returns at most one executable claim. It can
return empty after clearing 20 exhausted rows even when work remains; future
pollers must scan again. No scheduler is installed. Due pending jobs can be found
without notifications; abandoned jobs can be reclaimed after restart. A missed
notification that never created a job needs future outbox-to-job recovery/backfill.

A crash before claim commit leaves no attempt. A crash after commit leaves a
recoverable lease. A crash after external I/O but before outcome commit can repeat
the effect. Tokens fence database writes; they cannot stop an expired worker's
external I/O or promise exactly-once execution. Future storage operations must be
idempotent and address upload writer fencing separately. An abandoned final
attempt becomes `FAILED` on a later due scan, retaining identity and references.

## Verification

`BackgroundJobIntegrationTest` uses real PostgreSQL in the isolated
`background_job_test` schema of `vaultdrive_test`, applies V14, and validates JPA
mapping. It covers concurrent duplicate creation, competing claims, skip-locked
behavior, released claim locks, rollback/uncommitted visibility, stale outcomes,
expiry while waiting for a lock, backoff/recovery, known and abandoned exhaustion,
terminal duplicate replay, type filtering, and constraints. Cleanup removes only
test-created rows. Load the ignored environment in the Maven shell:

```powershell
cd backend
. .\.env.local.ps1
.\mvnw.cmd "-Dtest=BackgroundJobIntegrationTest" test
.\mvnw.cmd test
```

Execution results belong in task reports. No production load, process-kill,
multi-node failure, or physical cleanup claim follows from these tests.
