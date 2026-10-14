# Durable background jobs (Phase 2A)

`background_jobs` is the authoritative execution record. `outbox_events` records
notification delivery: `PUBLISHED` means broker acceptance, never completed work.
V14 and `com.vaultdrive.job` provide the Phase 2A persistence foundation. Phase 2B
adds an opt-in [notification consumer](purge-consumer.md) that creates jobs after
matching committed outbox intent. Phase 2C adds an opt-in [worker foundation](job-workers.md)
with scheduling, renewal and expired-claim recovery for registered handler types.
No API directly creates jobs. Phase 2D.1 adds a [file byte-purge handler](file-purge.md)
under the accepted trusted-writer assumption. Slice 2 adds conservative standalone
metadata finalization; Slice 3 adds V18 bounded continuation. Slices 4–5 add
folder discovery and file byte execution. Folder metadata removal and
reconciliation remain unimplemented.
Existing purge APIs and outbox publisher behavior are unchanged.

## Identity and creation

`BackgroundJobService.create(eventId, type, aggregateId)` uses the stable outbox
event UUID as the job primary key. One job is allowed per event, including after
success or failure. Concurrent inserts use `ON CONFLICT DO NOTHING`; a duplicate
returns the existing ID without resetting attempts, leases, or state. Reusing an
ID with a different type or aggregate is rejected. Distinct events for the same
aggregate are allowed; future consumers must validate intent against business
state. This persistence layer is not an authorization boundary.

Creation joins the caller transaction, or starts one if absent. The Phase 2B
consumer acknowledges only after its ingestion transaction commits. Rollback leaves no job.
Neither aggregate nor event has a foreign key: jobs must survive business metadata
and outbox retention. Terminal retention must preserve deduplication identity;
deleting a job would allow replay to recreate work. No retention/reset tooling exists.

Only `FILE_PURGE`/`FILE` and `FOLDER_PURGE`/`FOLDER` are supported. Jobs store no
payload, names, object keys, credentials, or exception text. The file handler uses
the separate immutable event-ID purge target and reloads ownership, ancestry and
pending-file eligibility. Missing legacy targets fail closed; jobs never infer keys.

## State machine

| Current state | Trigger | Result |
|---|---|---|
| absent | create with stable event ID | `PENDING`, zero claims/failures/checkpoints |
| `PENDING`, due | claim | `RUNNING`, increment claim count, capture checkpoint baseline, fresh token/lease |
| `RUNNING`, live matching token | complete | `SUCCEEDED`, terminal timestamp, clear claim |
| `RUNNING`, live matching token | renew | same token/attempt, extend lease and crash-retry deadline |
| `RUNNING`, live matching token, new committed checkpoint | CONTINUE | `PENDING`, one-second delay, clear claim, preserve counters |
| `RUNNING`, live matching token, no new checkpoint | CONTINUE | count one `NO_PROGRESS` failure, delayed retry or `FAILED` |
| `RUNNING`, live matching token | retry | count one failure; delayed `PENDING` below ten, otherwise terminal `FAILED` |
| `RUNNING`, expired lease and due retry | recovery | count one abandonment; new claim below ten, otherwise terminal `FAILED` |
| any | identical duplicate create | unchanged |

`SUCCEEDED` and `FAILED` are terminal. Completion/retry/continuation with a stale token or expired
lease returns false without changing state. No automatic reset exists. Only
`WORK_FAILED`, `LEASE_EXPIRED` and `NO_PROGRESS` failure codes are stored. Completion callers
must establish the work outcome first; this foundation cannot prove deletion.

## Claims and recovery

`claimNext(type)` uses an independent short JPA transaction, locking only job rows
ordered by retry time, creation time, and ID. Protected state is read through
`FOR UPDATE SKIP LOCKED`. Competing workers skip locked candidates and cannot
simultaneously acquire the same live claim. No hierarchy/resource locks are acquired.
Completion/retry/continuation lock the identified job row, check ownership using PostgreSQL's
clock after acquiring the lock, then conditionally update by status, token, and
unexpired lease. Retry uses persisted attempts, not caller snapshot metadata.

Claims commit before returning an immutable snapshot. Future orchestration must
run outside an enclosing transaction: claim, external I/O, and outcome recording
are separate steps. `REQUIRES_NEW` suspends an outer transaction; it does not close
it. Callers must hold no outer transaction/hierarchy lock across I/O. Resource
mutations will need their own ADR-0007 lock order and race tests. This foundation
never reads or locks file/folder rows.

V18 preserves `attempt_count` as a monotonic BIGINT claim/execution count and adds
`failure_count` for the maximum ten failed or abandoned executions. CONTINUE and
SUCCESS consume no failure budget; failures are never reset by progress. The lease
remains 30 seconds. Failure ordinal `f` uses `min(2^(f-1), 60)` seconds of backoff.
Known failures schedule from database time at failure recording. Each claim persists
its recovery deadline as lease expiration plus backoff for `failure_count + 1`;
abandonment is charged exactly once by the later locked recovery scan. Both
lease and retry deadline must pass before reclaiming. PostgreSQL's clock governs
deadlines, avoiding worker-host skew. Phase 2C adds fenced renewal using the same
job row lock and unexpired-token checks. It extends expiration to at least database
time plus 30 seconds and moves the crash retry deadline to expiration plus the
next failure's backoff, without changing counters or token. The worker
foundation also enforces conservative monotonic local lease/runtime limits.

Recovery is part of `claimNext`: each call examines at most 20 eligible rows,
marks exhausted claims failed, and returns at most one executable claim. It can
return empty after clearing 20 exhausted rows even when work remains; future
pollers must scan again. The opt-in Phase 2C scheduler drives these scans only for
registered handler types; Phase 2D.1 registers the file handler only. Due pending
jobs can be found without notifications; abandoned jobs can be reclaimed after restart. A missed
notification that never created a job needs future outbox-to-job recovery/backfill.

A crash before claim commit leaves no attempt. A crash after commit leaves a
recoverable lease. A crash after external I/O but before outcome commit can repeat
the effect. Tokens fence database writes; they cannot stop an expired worker's
external I/O or promise exactly-once execution. Future storage operations must be
idempotent and address upload writer fencing separately. An abandoned final
attempt becomes `FAILED` on a later due scan, retaining identity and references.

## Continuation checkpoint contract (Phase 2D.2 Slice 3)

`checkpoint(claim, expectedVersion)` is MANDATORY-transaction and must be the last
database operation in a short transaction that newly advances durable business
work: a cursor and its discovered members, a receipt, or a completed phase/unit.
No heartbeat, empty scan, repeated receipt, or replay counts as new progress.
The caller commits that work and checkpoint together, with no storage I/O inside
the transaction. Acquire hierarchy, fresh resource, and work/progress locks first,
then the job lock; never acquire resource locks after the job lock.

The checkpoint is a job-local revision, starting at zero and advancing by exactly
one through a compare-and-set against `expectedVersion`. It is evidence that a
work transaction committed, not a cursor or success flag. The handler retains its
actual cursor/receipt in its own durable work state. Duplicate versions and stale
or expired claims throw and roll back all work in the transaction. The final write
checks the exact token and PostgreSQL-clock lease after the job-row lock wait.
Database constraints and a trigger prohibit decreasing claims, failures, progress,
or claim baselines, and prohibit changing the fixed failure budget.

Each claim captures `claim_progress_version = progress_version`. `continueJob`
accepts only a live token with `progress_version > claim_progress_version`, checks
the predicate again at its write, and commits the same identity as delayed PENDING.
It preserves failure history, counters and durable progress. Returning false for
no progress records one fenced NO_PROGRESS failure; callers must not retry again.
A stale return changes nothing. An uncertain outcome is recovered from persisted
state, with no fabricated second transition.

A crash after work/checkpoint commit but before CONTINUE retains progress and
charges abandonment when recovered. The new claim captures that checkpoint as
its baseline: it must commit further useful progress to CONTINUE, or SUCCESS if
the prior work finished the job. Old progress cannot justify endless empty yields.
The foundation enforces transaction, CAS, monotonicity, and per-claim evidence;
each future handler must establish the business meaning of its checkpoint and
test its atomic cursor/receipt writes. No production handler returns CONTINUE yet.

V18 backfills failures as prior claims minus the unresolved RUNNING or successful
SUCCEEDED execution; PENDING/FAILED claims were all failed/abandoned. It preserves
terminal state, tokens and dates. V14–V17 remain unchanged. Deploy this accounting
change with old worker processes stopped: old binaries still spend claim counts.

## Verification

`BackgroundJobIntegrationTest` uses real PostgreSQL in the isolated
`background_job_test` schema of `vaultdrive_test`, applies migrations through V18, and validates JPA
mapping. It covers concurrent duplicate creation, competing claims, skip-locked
behavior, released claim locks, rollback/uncommitted visibility, stale outcomes,
expiry while waiting for a lock, backoff/recovery, known and abandoned exhaustion,
terminal duplicate replay, type filtering, constraints and fenced renewal/deadline
extension, including expiry during a renewal row-lock wait. Cleanup removes only
test-created rows. Slice 3 adds fifteen checkpointed continuations then SUCCESS,
mixed continuation/retry/abandonment exhaustion, no-progress exhaustion, atomic
test work/checkpoint rollback, duplicate/stale fencing, continuation lock-wait
expiry and V17-to-V18 legacy accounting migration fixtures. Load the ignored environment in the Maven shell:

```powershell
cd backend
. .\.env.local.ps1
.\mvnw.cmd "-Dtest=BackgroundJobIntegrationTest" test
.\mvnw.cmd test
```

Execution results belong in task reports. No production load, process-kill,
multi-node failure, or physical cleanup claim follows from these tests.
