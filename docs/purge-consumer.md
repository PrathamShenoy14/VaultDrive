# Purge notification consumer (Phase 2B)

The consumer transfers existing v1 purge notifications into durable PostgreSQL
jobs. It never claims or executes a job, changes file/folder metadata, or calls
Garage. PostgreSQL is authoritative; a RabbitMQ ACK means the notification has
a durable disposition, not that deletion is complete.

## Startup and configuration

`PURGE_CONSUMER_ENABLED` / `vaultdrive.purge.consumer.enabled` defaults to `false`,
including in the test profile. After verifying connectivity and inspecting the
intended queue, explicitly enable it in private deployment configuration. Enabling
it consumes real notifications and creates jobs/quarantine records; this slice
does not enable or deploy a running development API.

The consumer shares the existing `spring.rabbitmq.*` connection configuration and
uses `vaultdrive.outbox.publisher.queue` (default `vaultdrive.purge.requests`).
Publisher exchange, quorum queue, binding and notification behavior are unchanged.
The consumer declares no queues, exchanges, DLX arguments or policies. The existing
queue must be available before startup. Use the localhost SSH tunnel and private
credentials described in [the publisher runbook](outbox-publishing.md).

Each instance uses one consumer with prefetch one and manual acknowledgements.
Additional instances are safe through PostgreSQL job identity uniqueness. Automatic
dispatch and recovery scheduling are supplied separately by the opt-in
[Phase 2C worker foundation](job-workers.md). Production file and folder purge handlers are registered; worker enablement permits execution.

## Validation and acknowledgement order

1. Parse an object envelope of at most 65,536 bytes. Reject malformed JSON,
   duplicate keys, trailing tokens, noncanonical UUIDs, missing/wrongly typed
   required fields, and identity contradictions. Compatible extra fields are ignored.
2. Require `payloadVersion` to be integer `1`; accept only `FILE_PURGE_REQUESTED`
   with `FILE`/`fileId`, or `FOLDER_PURGE_REQUESTED` with `FOLDER`/`folderId`.
   Require `eventId`, `aggregateId`, `createdAt`, `payload.ownerId` and the resource
   UUID. AMQP `message_id` must match `eventId`; AMQP type, if present, must match
   event type. The payload resource ID must match the aggregate ID.
3. In a short independent JPA transaction, load the committed immutable outbox
   event and compare type, version, aggregate, owner/resource IDs and creation time.
   Unknown or mismatched events are invalid. Do not require `PUBLISHED`: a delivery
   may arrive before publisher outcome commit or during publication replay.
4. Call `BackgroundJobService.create` in that same transaction, using event UUID
   as job ID. The primary key and `ON CONFLICT DO NOTHING` make duplicates harmless,
   preserving pending, running and terminal job progress. Conflicting job identity
   is invalid. Let transaction commit complete before returning to the listener.
5. Send `basicAck(deliveryTag, false)` outside every database transaction. No
   RabbitMQ channel transaction or distributed transaction is used.

The listener rejects an enclosing database transaction before processing. The
ingestion transaction reads immutable outbox state, then inserts/finds the job
identity. It acquires no hierarchy/resource locks and mutates no business rows.
Future workers must still revalidate ownership, ancestry, lifecycle and claims.

A rollback or commit failure produces no ACK. A crash after commit but before ACK
leaves a durable job; RabbitMQ redelivery finds that same job. An uncertain ACK
stops the consumer rather than repeating database work on the same delivery.
Notification delivery is at least once, not exactly once; physical execution belongs to the independently enabled workers.

## Retries and quarantine

Database access/transaction failures get at most three attempts per delivered
notification, each with its own transaction. Delays are one second then two
seconds, outside transactions. Failed attempts roll back. No `basicNack(requeue=true)`
loop is used. Exhaustion raises a sanitized fatal listener error; the container
stops and closes its consumer channel, making unacknowledged deliveries eligible
for broker redelivery. An interrupted delay or unexpected processing/ACK failure
also stops consumption. The budget resets on an explicit restart/redelivery; it
is not a global durable attempt counter. Existing job execution attempts remain
unchanged. Transport reconnection follows Spring AMQP defaults; this policy bounds
database attempts rather than connection establishment or database query duration.

Permanent validation failures are quarantined in PostgreSQL by V15, rather than
dead-lettered to a new broker queue. `notification_quarantine` stores only the
SHA-256 body fingerprint, a canonical event UUID if parsing reached it, a controlled
reason (`MALFORMED`, `UNSUPPORTED_VERSION`, `UNSUPPORTED_TYPE`, `UNKNOWN_EVENT`,
`EVENT_MISMATCH`, `IDENTITY_CONFLICT`) and database creation time. It stores no raw
body/headers, user names, credentials, object keys or exception messages. The body
fingerprint primary key deduplicates invalid replay; the first reason is retained.
Quarantine does not suppress later valid ingestion of the same event.

Only after the quarantine transaction commits does the listener ACK the invalid
delivery. This ACK means durably rejected, never completed work. Quarantine database
failure follows the same bounded retry/stop policy, so invalid messages are not
silently discarded during a database outage. No broker DLX or existing queue
argument change is required. Raw invalid bytes cannot be recovered from the ledger;
this is the deliberate privacy/diagnostic trade-off.

Consumer logs contain controlled stop codes only. Keep framework AMQP/client
loggers disabled as configured, because framework diagnostics may contain bodies
or credential-bearing connection details. Never log parser/database exceptions.

## Operational recovery

- When stopped for `DATABASE_UNAVAILABLE`, restore database connectivity, inspect
  queue backlog and PostgreSQL state, then explicitly restart the application or
  its `purgeNotificationContainer`. Do not configure a rapid restart loop: restart
  permits another three attempts. There is no automatic database-recovery scheduler.
- For `ACK_UNCERTAIN`, keep the existing job and let redelivery deduplicate it.
  Do not reset job attempts or mark it complete merely because the notification ACKed.
- Inspect quarantine by reason/time/event UUID. Diagnose unsupported versions or
  mismatches using the authoritative outbox event privately. After a code/data
  correction, explicitly replay a reviewed notification with the original event UUID;
  ingestion remains idempotent. No replay tool or automatic quarantine retry exists.
- Unknown/malformed events with no authoritative source cannot be reconstructed
  from their fingerprint. Retain outbox records for inbound validation/replay;
  premature outbox deletion causes even a duplicate notification to quarantine as
  `UNKNOWN_EVENT`. Outbox/job/quarantine retention policy remains undecided.
- Missing jobs for aged PUBLISHED sources can use [bounded Phase 2E.4 recovery](missing-job-reconciliation.md).
  Quarantined or invalid sources and historical pre-outbox intents remain blocked;
  this does not change valid consumer replay or automatically clear quarantine.
  Existing jobs survive broker loss and remain eligible for PostgreSQL execution/recovery.

## Verification

`PurgeNotificationTest`, `PurgeNotificationListenerTest`,
`NotificationIngestionIntegrationTest` and `PurgeConsumerConfigurationTest` cover
validation, sanitized failures, disabled startup, PostgreSQL commit/rollback,
duplicate/terminal replay, quarantine and acknowledgement ordering. Database tests
use only the `notification_ingestion_test` schema in `vaultdrive_test`.

`RabbitPurgeConsumerIntegrationTest` uses the existing isolated
`vaultdrive_outbox_test` vhost, UUID-suffixed publisher-compatible queues/exchanges
and the `notification_consumer_test` database schema. It exercises the production
container configuration, publisher-to-consumer delivery, invalid notifications,
bounded failure/stop, explicit restart/redelivery and commit-before-ACK loss.
Cleanup removes only test fixtures/topology. It never consumes the default queue.

```powershell
cd backend
. .\.env.local.ps1
# Supply existing broker credentials privately and keep the localhost tunnel open.
$env:RABBITMQ_TEST_VHOST = "vaultdrive_outbox_test"
.\mvnw.cmd "-Dtest=PurgeNotificationTest,PurgeNotificationListenerTest,NotificationIngestionIntegrationTest,PurgeConsumerConfigurationTest,RabbitPurgeConsumerIntegrationTest,RabbitOutboxIntegrationTest,BackgroundJobIntegrationTest" test
.\mvnw.cmd test
```

Execution results belong in task reports. Live tests are skipped without the
explicit isolated-vhost flag; that does not establish end-to-end verification.
Real process-kill, broker persistence/failover, load and long database outages are
not tested by these bounded integration scenarios.
