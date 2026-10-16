# Continuous asynchronous purge verification (Phase 2E.3)

[AsyncPurgeEndToEndTest](../backend/src/test/java/com/vaultdrive/e2e/AsyncPurgeEndToEndTest.java)
starts a real embedded HTTP server with production controllers, services, scheduled
outbox publisher, RabbitMQ transport/listener/ingestor, durable job worker and both
purge handlers. No service mocks, test-owned request transactions, direct job
inserts, manual handler dispatch or direct database phase advancement are used.
Normal application and test-profile processing defaults remain disabled.

## Isolation and startup

The test requires `ASYNC_E2E_TEST=true` and the existing approved
`RABBITMQ_TEST_VHOST=vaultdrive_outbox_test`. It fails before application startup
if required private environment variables, database access or topology isolation
cannot be established. It does not provision infrastructure, broker/database users
or permissions, vhosts, buckets or service credentials.

- PostgreSQL is fixed to localhost port 5432, database `vaultdrive_test`. Each run
  creates `async_e2e_<32 hex UUID characters>` using CREATE SCHEMA without
  IF NOT EXISTS. It never adopts an existing schema. Flyway V1-V22 and Hibernate
  use only that schema; schema auto-creation is disabled. Before initializing each
  mutation-capable async component, a gate verifies the database, current schema,
  search path and absence of all outbox events, jobs, users, files and folders.
  Consequently no retained test fixture or development work is eligible.
- RabbitMQ uses localhost port 5672 and only `vaultdrive_outbox_test`, regardless
  of the normal application vhost. Exchange and quorum queue names are
  `vaultdrive.outbox.events.test.<schema>` and
  `vaultdrive.purge.requests.test.<schema>`. Passive checks reject collisions;
  declarations and binding finish before Spring starts the consumer. The queue
  must initially have zero messages and consumers. No default topology is used.
- Garage uses the privately configured existing endpoint/bucket. A test-only key
  allocator generates a run-scoped `integration-tests/` prefix plus owner/file
  UUIDs. Bucket accessibility and per-key absence are checked before admitting
  uploads. Only absence-checked allocations enter the in-memory cleanup registry.
  Bytes still pass through the production ObjectStorageService, write admission,
  immutable allocation/target persistence and trusted-writer contract. No bucket
  listing, existing-object cleanup or storage identity migration occurs.

Command-line properties enable the three async components only inside this
manually owned test context, with 100 ms polls and one worker slot. They cannot
enable another application context. HTTP begins only after all three isolation
gates pass. OSIV remains false. The test binds its random HTTP port to loopback.

## Reproduce from Windows PowerShell

Start or reuse the existing localhost AMQP tunnel; do not create another if port
5672 is already forwarded. Keep this command running in a separate terminal and
close only that verification tunnel afterward:

```powershell
ssh -o BatchMode=yes -o ExitOnForwardFailure=yes -N -L 127.0.0.1:5672:127.0.0.1:5672 vaultdrive-vm
```

From `backend/`, with PostgreSQL/Garage already reachable and private broker/S3/DB
credentials in the ignored local environment file:

```powershell
. .\.env.local.ps1
$env:RABBITMQ_TEST_VHOST = 'vaultdrive_outbox_test'
$env:ASYNC_E2E_TEST = 'true'
.\mvnw.cmd '-Dtest=AsyncPurgeIsolationTest,AsyncPurgeEndToEndTest' test
if ($LASTEXITCODE -ne 0) { throw 'Continuous verification failed; inspect sanitized evidence' }
.\mvnw.cmd test
if ($LASTEXITCODE -ne 0) { throw 'Backend verification failed' }
Remove-Item Env:ASYNC_E2E_TEST
Remove-Item Env:RABBITMQ_TEST_VHOST
```

The full command includes this harness and all seven existing live broker tests.
Without `ASYNC_E2E_TEST=true` the harness is skipped; without the existing broker
opt-in the seven broker tests are skipped. Skips do not establish continuous
verification. Never enable OUTBOX_PUBLISHER_ENABLED, PURGE_CONSUMER_ENABLED or
JOB_WORKER_ENABLED in a development API for this procedure.

## Evidence and assertions

Registration/login, multipart upload, download, trash and permanent deletion use
actual HTTP requests and JWT security. Every upload must reach READY and return
the original bytes before trash. One standalone file produces one outbox event,
one same-UUID durable job, a PUBLISHED timestamp, consumer ACK after independently
visible job commit, successful worker execution, verified Garage absence, immutable
V16 target/allocation retention and V17 byte/finalization receipts.

The folder case contains root, child, grandchild and a separately trashed sibling
folder; five READY files span all depths, including a separately trashed file.
Only the root produces an event/job. Bounded polling must observe exactly
DISCOVER -> DELETE_FILES -> DELETE_FOLDERS -> FINALIZED. Retained manifests account
for four folders/five files; child removal timestamps strictly precede their
parents. All file bytes/metadata disappear, all member/removal receipts survive,
and the original folder job succeeds without spending failure budget. An unrelated
control root/child/file remains accessible through HTTP throughout both cases.

After successful continuous execution, the harness stops only its consumer,
publishes each retained event twice through the production transport (four positive
confirms), receives one message without ACK on a temporary channel and closes that
channel. Restarting the production consumer must observe real redelivery, six
total successful basicAck calls including the original two deliveries, exactly two
unchanged terminal jobs and unchanged outbox/receipt timestamps/counters. The queue
must be drained after stopping the consumer. No production event is rearmed or
edited. Rolled-back attempts to replace file/folder receipts must fail with SQLSTATE
23514. No invalid notifications or quarantine records are expected.

`ASYNC_E2E` evidence lines contain only run schema, event UUIDs, counts, phases and
booleans. Polls use selected scalar/timestamp fields in this small fresh schema,
three-second JDBC query limits, 50 ms intervals and 65-second deadlines; the whole
JUnit test has a 180-second timeout. No object keys, JWTs, credentials, raw payloads,
HTTP response bodies or target snapshots are printed. Keep framework AMQP/client,
SQL and bind-value logging disabled. Surefire reports under ignored `backend/target/`
provide the test result; do not publish private environment files or verbose logs.

## Cleanup and limitations

Normal and failure exits stop the test consumer, scheduled publisher and worker,
attempt deletion of every registered disposable key (a maximum of sixteen, seven
in this scenario), and close the application. Cleanup deletes only topology whose
creation succeeded in this run and drops only its newly created schema after byte
cleanup succeeds. If owned-byte cleanup fails, it retains the schema and allocation
identities for bounded recovery and fails verification. Database
statement and broker RPC waits are bounded. There is no global queue purge, shared
schema cleanup, bucket scan or deletion of pre-existing resources. Retained proofs
are asserted before disposing of the test schema; production retention is unchanged.

A JVM/host kill can bypass finally blocks. If interrupted, keep processing disabled
and review the exact emitted run schema plus its corresponding topology privately.
Use retained allocation rows to identify that run's objects; do not infer ownership
from arbitrary existing keys, drop shared schemas or sweep a bucket/vhost. Stalled
PUTs may have uncertain late outcomes; this harness supplies no orphan reconciler
or general cleanup command. Preserve evidence and obtain a bounded recovery decision
if cleanup fails. Restarting verification always allocates a fresh boundary.

This proves a bounded happy-path pipeline plus duplicate publication and real broker
redelivery in one JVM, one PostgreSQL instance, one broker and one Garage binding.
It does not prove process-kill recovery, broker restart/failover, production load,
multi-node availability, storage-side fencing, unsafe-writer quiescence, legacy
backfill, retention/replay operations or production readiness. Existing component
race/fencing/failure tests retain their separate evidence. No VM/Compose service,
credential, development database or existing Garage object is changed. See
[architecture](architecture.md), [file purge](file-purge.md),
[folder purge](folder-purge-design.md), [publisher](outbox-publishing.md),
[consumer](purge-consumer.md) and [workers](job-workers.md).


## Verification record

On 2026-10-16 the final focused run passed all three tests, including both preflight
rejection tests. The final full backend suite passed 672 tests with zero failures,
errors or skips, including the continuous harness, real PostgreSQL/Garage suites,
all three live outbox broker tests and all four live consumer broker tests.

The final full-suite continuous scenario used schema
`vaultdrive_test.async_e2e_64141d580ddc41dca104c02acc4f6ed8` and the existing vhost
`vaultdrive_outbox_test`. Its exact disposable exchange was
`vaultdrive.outbox.events.test.async_e2e_64141d580ddc41dca104c02acc4f6ed8`;
its quorum queue was
`vaultdrive.purge.requests.test.async_e2e_64141d580ddc41dca104c02acc4f6ed8`.
File event/job `0b212d87-6390-4a5f-a3b7-ca9328cfa454` succeeded in one claim;
folder event/job `e21f9e60-e1b8-41c2-812e-8d05301209b1` succeeded in thirteen.
Both had zero failed executions. All four folder phases, four confirmed duplicate
publications, six ACKs, real redelivery, unchanged proof and readable controls
passed. All seven allocated object keys were cleaned. Schema removal was
independently queried; owned queue/exchange deletion completed. Production code,
migrations and disabled defaults were unchanged. Documentation links, task-source
private-value exclusions and git diff --check passed.

Some verification runs logged a Tomcat 11.0.24 MimeHeaders NullPointerException on
asynchronous download response handling despite successful HTTP status/byte
assertions. This occurred in an earlier full run and the final focused run; the
final full-suite harness run did not log it. Its cause and production impact remain
unproven; no streaming/server refactor is included in this bounded task. Tomcat also
reported the AWS SDK global response-input-stream-timeout-scheduler thread at
context shutdown. That warning's lifecycle/classloader impact is uninvestigated;
the harness does not globally terminate SDK executors shared with other test
contexts. Keep these observations separate from assertions that passed.
Process-kill/failover/load and operational recovery gaps above remain open.
