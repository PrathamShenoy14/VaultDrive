# Isolated process-loss recovery verification (Phase 2E.5A)

Consolidation update: HTTP fixture uploads now use direct multipart through the test-only `MultipartHttpUploads` helper. Existing isolation, one-dispatch uncertainty and process-kill gates remain. Historical results below describe their recorded revisions; see [current milestone verification](backend-consolidation.md#verification) for runs executed after this change.

The test-classpath-only `AsyncPurgeProcessRecoveryTest` launches
`com.vaultdrive.e2e.ProcessRecoveryApplication` in a separate JVM. The default
`release` mode pauses at real transaction boundaries, releases the pause, stops
the child cooperatively and restarts it to verify retained proof. It never calls
forced termination. `kill` mode is separately gated by explicit approval for the
exact live process. No real process-kill result is claimed by implementing or
passing release mode.

## Prerequisites and isolation

Reuse the existing [Phase 2E.3 prerequisites](async-purge-verification.md), private
`.env.local.ps1`, PostgreSQL/Garage connectivity and localhost AMQP tunnel. Do not
change Compose, VM services, broker users, credentials, permissions or buckets.
Use only the existing `vaultdrive_outbox_test` vhost.

Each scenario freshly CREATEs an `async_e2e_<32 hex UUID>` schema in
`localhost:5432/vaultdrive_test`, and collision-checks a dedicated durable direct
exchange/quorum queue named `vaultdrive.outbox.events.test.<schema>` /
`vaultdrive.purge.requests.test.<schema>`. Flyway V1-V23 and Hibernate use only this
schema. Schema creation is disabled in Spring. Each enabled autonomous component
checks the database and exact single-schema search path before initialization.
Initial startup also requires empty business/outbox/job tables. Restart requires
the fixture journal and its one retained intent. The localhost HTTP server uses a
random loopback port. No normal development process is discovered or controlled.

Garage allocations use only absence-checked disposable `integration-tests/`
allocations under this run schema. A forced-to-disk UUID allocation journal is
written before admitting PUT, including before uncertain/rolled-back uploads.
Only these allocations can be cleaned (maximum sixteen). A storage-binding digest
must match on restart/cleanup. Original deletion targets still come from V16/V17
through production `ObjectStorageService`; the journal is only for owned cleanup.
There is no bucket listing or existing-object cleanup.

All run journals live under ignored `backend/target/process-recovery/<schema>/`.
Keep these until `resources-cleaned` exists. Do not run Maven clean or delete
target while a run is active or retained. Do not edit journals or reuse a schema.
They contain UUIDs, PID/start-time/run-token identities, scalar markers, classpath
paths and binding/identity digests; no object keys, credentials, JWTs or payloads.
Framework logging is disabled in child processes; failures print only exception
class names. Do not enable SQL/bind, HTTP, AMQP or SDK debugging.

## Safe reproduction: no forced process termination

From `backend/` in PowerShell, with the existing verification tunnel open:

```powershell
. .\.env.local.ps1
$env:RABBITMQ_TEST_VHOST = 'vaultdrive_outbox_test'
$env:ASYNC_PROCESS_TEST = 'true'
.\mvnw.cmd '-Dtest=ProcessRecoveryProtocolTest,AsyncPurgeIsolationTest,AsyncPurgeProcessRecoveryTest' '-DprocessRecovery.mode=release' test
if ($LASTEXITCODE -ne 0) { throw 'Safe process harness verification failed' }
Remove-Item Env:ASYNC_PROCESS_TEST
$env:ASYNC_E2E_TEST = 'true'
.\mvnw.cmd '-Dlogging.level.root=OFF' '-Dlogging.level.org.hibernate=OFF' '-Dspring.jpa.show-sql=false' test
if ($LASTEXITCODE -ne 0) { throw 'Backend verification failed' }
Remove-Item Env:ASYNC_E2E_TEST
Remove-Item Env:RABBITMQ_TEST_VHOST
```

The release matrix has seventeen scenarios. Each missing-job case naturally waits
at least the default five minutes after publication, with the consumer disabled;
allow roughly twenty-five minutes for the matrix depending on JVM startup. No clocks,
leases, retry deadlines, attempts or progress are edited. Normal 30-second leases,
persisted backoff and ten-failure budgets apply. Child polling uses 100 ms and one
worker slot; reconciliation polls once per second only in its isolated restart.
All normal processing/reconciliation defaults remain disabled.

For a bounded subset, append for example:
`'-DprocessRecovery.scenarios=FILE_RECEIPT,FOLDER_CHILD_REMOVAL'`.
Unknown scenario names fail rather than silently selecting no work.

## Boundaries and expected durable evidence

| Scenario suffix | Pause, with no database transaction open | Recovery assertion |
|---|---|---|
| INTENT | Committed intent/target/run; before publisher claim | Original pending event publishes and creates the same-UUID job |
| CONFIRM | Positive real broker confirm; before PUBLISHED transaction | Publication can replay the original UUID; one terminal job |
| ACK | Independently visible job commit; before basicAck | Redelivery deduplicates the original untouched job |
| CLAIM | Committed worker claim; before executor dispatch | Expiry plus persisted retry deadline precede a fresh claim |
| DISCOVERY | Committed discovery page/checkpoint; before CONTINUE | Original manifest/frontier/cursors and progress persist |
| DELETE | Actual Garage DELETE returned; before byte receipt | HEAD confirms absence; retry uses the unchanged original target |
| RECEIPT | Byte receipt committed; before metadata finalization | Original receipt survives; standalone replay skips DELETE |
| CHILD_REMOVAL | Non-root removal receipt/checkpoint committed | Root remains pending; bottom-up removal resumes |
| FINALIZED | Root removal/FINALIZED/checkpoint committed; before job outcome | Retained FINALIZED proof completes the original job |
| MISSING_JOB | PUBLISHED committed with consumer disabled and no job | Restart reconciler alone materializes the original UUID after five real minutes |

FILE covers INTENT, CONFIRM, ACK, CLAIM, DELETE, RECEIPT and MISSING_JOB. FOLDER
covers all ten boundaries with root/child/grandchild and one READY file per level.
Only a root folder event/job exists. No descendant jobs are invented.

Hooks wrap the production transaction proxies on the test classpath. A hook
refuses to signal within an active transaction. Paused handler renewals also wait
at the test gate; no transaction or hierarchy lock is held across the pause. Stop
or interruption cancels a held boundary; it does not silently release it into more
work. Gates and test JVMs have fifteen-minute safety lifetimes. A process may
retain an abandoned lease; the harness never rearms it.

Assertions cover immutable allocation/standalone-target digests, matching V17
storage identity, retained byte/metadata/folder-removal/finalization receipts,
monotonic frontier/cursors and progress, same event/job UUID, exact abandonment
failure charge for a killed RUNNING claim, and rejection of its old token by
complete/retry/continue/renew after recovery. Stale publisher tokens must also
fail ownership/confirm/failure recording. Fresh claims must use a different token
and start after both persisted recovery deadlines. Original objects must HEAD 404 and
an unrelated control must still download its original bytes. Nested removal
timestamps strictly precede parents. Except missing-job cases, replay publishes
the retained event twice through production transport, closes an unacknowledged
delivery channel, observes real redelivery/ACKs, and requires unchanged terminal
accounting and receipts. Missing-job queues intentionally retain their original
notification until owned topology disposal; RabbitMQ is not involved in creating
their recovered jobs.

## Approval-gated real process loss

Do not run kill mode as part of unattended verification. Explicit approval may
cover one scenario or the bounded seventeen-scenario matrix of newly launched
test children. Matrix approval never waives the per-child identity report,
ownership/isolation/cleanup checks or exact one-use permit. Stop a scenario
without termination if any check fails. For one scenario:

```powershell
. .\.env.local.ps1
$env:RABBITMQ_TEST_VHOST = 'vaultdrive_outbox_test'
$env:ASYNC_PROCESS_TEST = 'true'
.\mvnw.cmd '-Dtest=AsyncPurgeProcessRecoveryTest' '-DprocessRecovery.mode=kill' '-DprocessRecovery.scenarios=FILE_DELETE' test
```

The parent reaches the deterministic boundary, checks durable state and emits
`AWAITING_EXPLICIT_APPROVAL` with scenario, PID, immutable process start instant,
run token, schema, journal directory and exact permit path. It then waits at most
ten minutes. Before approving, report that exact identity and the following impact:
only this separate test application JVM will lose its in-memory state/connection;
its schema, dedicated topology and disposable bytes remain for restart. The
normal development application, Docker services and other processes are unaffected.
Cleanup is limited to the recorded allocations, queue, exchange and schema after
all test processes stop. A failure retains them for inspection.

Obtain explicit user approval covering that test child before writing a permit.
Under an explicitly authorized matrix, report and verify each fresh identity
before issuing its permit; never permit directories from an earlier run. In a
second terminal, only within that authorization, copy the exact emitted identity:

```powershell
# Set these to the EXACT emitted values after explicit approval.
$permitPath = '<journal directory>\permit-<run token>.txt'
$identity = '<PID>|<UTC start instant>|<run token>|<schema>'
$permitTemporary = Join-Path (Split-Path $permitPath) ('.approval-' + [Guid]::NewGuid())
[System.IO.File]::WriteAllText($permitTemporary, $identity)
Move-Item -LiteralPath $permitTemporary -Destination $permitPath -ErrorAction Stop
```

A stale/mismatched permit fails. The parent rechecks PID/start time, executable,
test entry point/run directory/token where OS arguments are available. Windows
Java omits arguments, so the fallback requires matching forced-to-disk launcher
and child identity journals plus a fresh random live challenge/response from
that child before proceeding. A normal application cannot answer this protocol.
The permit is consumed once. The only `destroyForcibly` targets the parent's
positively identified initial child, never a PID search result. If ownership
cannot be established, nothing is killed. Restart uses the same schema/topology
without editing durable work. No deadline is shortened to speed recovery.

Successful release results do not authorize this step. Seven FILE and ten FOLDER
scenarios make up the approval-gated matrix. To reproduce the entire matrix under
explicit scoped authorization, omit the scenario filter:

```powershell
. .\.env.local.ps1
$env:RABBITMQ_TEST_VHOST = 'vaultdrive_outbox_test'
$env:ASYNC_PROCESS_TEST = 'true'
.\mvnw.cmd '-Dtest=AsyncPurgeProcessRecoveryTest' '-DprocessRecovery.mode=kill' test
if ($LASTEXITCODE -ne 0) { throw 'Inspect failed scenarios and retained journals' }
Remove-Item Env:ASYNC_PROCESS_TEST
```

Each new process still waits for its own exact permit. Use the inspection and
cooperative stop/cleanup commands below on interrupted or failed runs. Close only
verification-owned temporary forwarding connections after all checks; leave
existing development tunnels and SSH sessions untouched.

## Inspection, safe stop and interrupted-run cleanup

Use the ignored classpath journal from a run. From `backend/`:

```powershell
. .\.env.local.ps1
$env:RABBITMQ_TEST_VHOST = 'vaultdrive_outbox_test'
$run = (Resolve-Path 'target\process-recovery\<exact schema>').Path
$cp = Get-Content -LiteralPath "$run\test-classpath.txt" -Raw
& "$env:JAVA_HOME\bin\java.exe" -cp $cp com.vaultdrive.e2e.ProcessRecoveryControl inspect $run
& "$env:JAVA_HOME\bin\java.exe" -cp $cp com.vaultdrive.e2e.ProcessRecoveryControl stop $run
& "$env:JAVA_HOME\bin\java.exe" -cp $cp com.vaultdrive.e2e.ProcessRecoveryControl cleanup $run
```

`inspect` prints identities/liveness and scalar run state. `stop` verifies live
ownership and writes a cooperative stop request; it never kills. The parent also
requests cooperative stop on failure and waits thirty seconds. On a stop request,
paused work is cancelled and the child closes its Spring context. Its independent
fifteen-minute lifetime also closes the context if the parent disappears. Do not
close the parent terminal as the primary stop procedure. Do not use taskkill,
Stop-Process, process-name matching, Docker stop or queue purge.

`cleanup` first ensures recorded processes are stopped, then launches a cleanup
child with every autonomous component explicitly disabled. It verifies the
binding digest and attempts every journaled disposable allocation through
ObjectStorageService. Only successful byte cleanup permits queue/exchange/schema
disposal. Active consumers block cleanup. All connection/RPC/query/child waits
are bounded. A failed/uncertain cleanup retains evidence and refuses to overwrite
a previous cleanup-process identity. Inspect that case privately before any
manual action. Do not delete another run's resources or scan a bucket/vhost.

If interruption occurs before `resources-owned` is durably written, the control
command refuses adoption. Keep the schema/topology identity privately and review
creation success before a separately bounded cleanup decision. Likewise, after
partial topology/schema disposal, retain the journal and review exactly which
resources remain; this is not a general orphan reconciler. These fail-closed
limitations avoid inferring ownership of pre-existing resources.

Normal success stops the restarted child, checks no consumers remain, cleans
objects and deletes only the owned topology/schema. `resources-cleaned` is the
completion marker. A retained journal does not mean the underlying resources
still exist; inspect first. Never purge the shared vhost or drop shared schemas.

## Evidence and limits

JUnit uses per-scenario 1,100-second outer timeouts, bounded startup/failpoint,
approval, recovery, replay and cleanup waits; database queries use three seconds.
The verifier reuses its one owned autocommit connection for fresh observations.
Child logs and Surefire results remain under ignored target. Share only controlled
`PROCESS_RECOVERY` lines and test totals. A skip, retained run or failed cleanup
is not successful verification.

Release mode establishes wiring, durable boundary evidence, real elapsed
reconciliation eligibility, cooperative restart and duplicate behavior. It cannot
establish abrupt process-loss recovery. Real kill mode covers one application JVM
in this single PostgreSQL/broker/Garage binding; broker/VM/host restart, power loss,
multi-node failure, production load, uncertain PUT reconciliation and malicious
storage writers remain outside this harness. The trusted-writer and immutable
storage-binding assumptions remain those of ADR-0008. No throughput claim is made.

## Executed non-destructive verification (2026-10-16 UTC)

- The seventeen-scenario release matrix passed: 26 checks including isolation
  and protocol tests, zero failures/errors/skips, 23 minutes 57 seconds.
- The final focused rerun passed all 13 checks: three process scenarios, two
  isolation checks and eight protocol checks, zero failures/errors/skips.
- The full backend suite ran 720 tests: 719 passed, zero failures/errors, one
  intentional skip of AsyncPurgeProcessRecoveryTest with its opt-in removed.
  All seven live RabbitMQ tests, PostgreSQL/Garage suites and continuous HTTP
  pipeline test ran successfully. The process matrix was verified separately.
- Live ownership inspection and retained-run cleanup were exercised. All owned
  schemas, topology and disposable allocations from harness runs were cleaned;
  the verification-only SSH tunnel was closed after explicit approval.

At that milestone no application process was force-terminated and no kill permit
was supplied. The separately authorized execution below provides later evidence.

## Executed real process-loss verification (2026-10-17 IST / 2026-10-16 UTC)

The user explicitly authorized only newly harness-launched test application
children for this matrix. Before every permit, the exact PID/start/run-token/schema
identity was reported, launcher/child/plan journals matched, a fresh live ownership
challenge passed, and dedicated persistence/topology and disposable allocation
journals were checked. The parent consumed each permit and repeated ownership
verification immediately before its original Process object's destroyForcibly.
No shell process-kill mechanism was used for application crashes.

| Boundary | Standalone file | Nested folder |
|---|---|---|
| INTENT | Passed after hook correction | Passed |
| CONFIRM | Passed | Passed |
| ACK | Passed | Passed |
| CLAIM | Passed | Passed |
| DISCOVERY | Not applicable | Passed |
| DELETE | Passed | Passed |
| RECEIPT | Passed | Passed |
| CHILD_REMOVAL | Not applicable | Passed |
| FINALIZED | Not applicable | Passed |
| MISSING_JOB | Passed | Passed |

All seventeen authorized crashes recovered the original event/job identity,
unchanged storage target, retained receipts/checkpoints, valid fencing and final
metadata/storage state. Controls remained readable before owned cleanup. All
fifteen broker-consumer cases also passed duplicate publication and real
redelivery assertions with unchanged terminal proof. Both missing-job cases kept
the consumer disabled and waited at least five real minutes after PUBLISHED;
their original notifications remained until owned topology cleanup. Abandoned
worker/publisher claims waited their persisted leases and retry deadlines; no
attempt, lease, clock or progress was rewritten.

The first matrix invocation passed sixteen crash cases and failed FILE_INTENT's
durable-boundary assertion before permit issuance or termination. An empty-table
observation could race the first commit into claimNext. Test-only advice now holds
the first publication claim outside a transaction until the intent is committed,
then pauses before claiming. That failed run stopped cooperatively and its owned
resources were cleaned. The corrected FILE_INTENT kill/restart passed separately.
Production code, configuration, migrations and disabled defaults were unchanged.

Post-verification checks passed:

- Focused protocol/isolation plus corrected file/folder INTENT release checks:
  12 tests, zero failures/errors/skips (2 minutes 29 seconds).
- Corrected FILE_INTENT kill/restart: one test, zero failures/errors/skips.
- Full backend suite: 720 tests, 719 passed, zero failures/errors, one intentional
  skip of the process harness with its opt-in removed. Live RabbitMQ,
  PostgreSQL/Garage and continuous HTTP pipeline checks ran.
- Independent read-only inspection of all twenty run journals from this session
  confirmed no live owned children, schemas, queue/exchange pairs or remaining
  allocations; all 62 journaled allocations returned HEAD 404. This includes the
  safely stopped failure, two release checks and seventeen successful crashes.
- Sanitized evidence remains under ignored target: process-recovery-results.log,
  process-recovery-matrix-evidence.csv, the final inventory summary and durable
  per-run journals. Full local Maven output is retained separately; review it
  privately rather than sharing unfiltered framework output.
- The verification-owned temporary AMQP forwarding connection was closed after
  testing. Existing tunnels, development applications and VM services were left
  running; no infrastructure, credentials or production configuration changed.

No scenario in this matrix remains pending authorization. This is one application
JVM's loss at the listed boundaries, not broker/VM/host restart, power loss,
multi-node failure, production-load verification or uncertain PUT reconciliation.
The trusted-writer/immutable-binding assumptions and interrupted partial-resource
adoption limitations above remain unchanged.
