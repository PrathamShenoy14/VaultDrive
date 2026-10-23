# MVP storage quota notifications

Operational extension: [MVP hardening](operational-hardening.md) supplies required
consumer readiness and bounded audited local replay of the original retained event
after publication loss/quarantine/code correction. It preserves owner/event
deduplication and read state. There is still no autonomous notification scanner;
logical expiry/accounting never establishes physical reclamation. Historical
statements below about absent replay tooling predate this extension.

V44 adds owner-only in-app alerts for **80%, 90% and 100%** logical charge.
Charge is `used_bytes + reserved_bytes`, including hidden copy members, Trash
and pending purge. Uncertain allocations and physical Garage blocks are separate.
An Editor's reservation alerts the storage owner, never the Editor.

## Threshold semantics and atomicity

For each threshold T, reached means charge is positive and
`charge * 100 >= limit * T`. PostgreSQL numeric arithmetic avoids overflow and
rounding; with a three-byte limit all three thresholds are first reached at three
bytes. An empty zero-limit account produces no alert; positive retained charge
at zero limit reaches all three thresholds. A jump emits every newly crossed
threshold, rather than only the highest. Rejected over-quota admission emits none.

The existing locked quota row itself is the threshold state. V44's AFTER UPDATE
trigger compares OLD and NEW charge/limit for each boundary. Falling **strictly
below** rearms that threshold; staying exactly on it does not. A later crossing
gets a new event UUID even if the previous notification is unread or undelivered.
There is no time-based cooldown or dependency on notification delivery/read state.
Every successful accounting transition is evaluated, including multiple transitions
in one transaction; rollback removes all resulting events. Existing usage is the
rollout baseline, without retrospective alerts or a startup scan.

Operator limit decreases can cross thresholds even without another upload;
increases can rearm them. No-op updates do neither. Explicit counter repair uses
the same semantics and may emit alerts if the repaired charge crosses a boundary.
Limits and accounting must never be edited with triggers disabled in operation.

`storage_quota_events` retains immutable event UUID, owner, threshold, logical
usage, limit and PostgreSQL occurrence time, without account/resource cascades.
Its source row and `STORAGE_QUOTA_THRESHOLD_CROSSED` v1 outbox row commit in the
same transaction as the accounting mutation. The event's UUID is also the
`STORAGE_QUOTA_EVENT` aggregate UUID; payload is only `{quotaEventId: "<uuid>"}`.
The snapshot stays historical after later usage, limit or resource changes.

The existing file trigger still enforces capacity from fresh locked quota state.
READY transfers reserved to used without changing charge or emitting an alert.
Eligible FAILED/removal can rearm thresholds, while uncertainty, completion
decisions/receipts, cancellation of a whole folder-copy job and abort acknowledgements
alone cannot. Idempotent upload/copy admission, completion/recovery, cancellation,
expiry and folder-copy checkpoint replays do not duplicate accounting crossings.

Lock order remains [ADR-0007](decisions/0007-concurrency-locking.md): existing
hierarchy/resource/lifecycle coordination, then the quota row at the existing
accounting boundary, then new source/outbox inserts only. Limit-only SQL UPDATE
locks only the quota row and must not subsequently acquire resource locks.
Counter repair retains exclusive hierarchy, quota row, fresh aggregate order.
No broker, signing or Garage I/O occurs in these transactions. No separate quota
lock class, worker, queue, scheduler or notification framework is introduced.

## Delivery and authenticated API

The existing publisher routes quota events alongside permission activity on
`permission-queue` / `permission-routing-key` (defaults
`vaultdrive.permission.activity` / `permission.activity`). The existing
`SHARING_NOTIFICATIONS_CONSUMER_ENABLED` switch enables both kinds; publisher and
consumer remain independently disabled by default. The historically named
permission listener/ingestor and `sharing_notifications` table are intentionally
reused. Purge routing, jobs and missing-job reconciliation remain separate.

Quota messages require the publisher's AMQP type, message UUID and strict JSON
envelope. Ingestion validates type/version/aggregate/pointer/timestamp against the
committed outbox and immutable source before inserting the source owner as the
only recipient. Quota values and recipients are never accepted from broker payloads.
The conditional database source guard preserves grant recipient checks and adds
owner validation for quota sources. It replaces the permission-only source FK;
both source tables forbid UPDATE/DELETE, preserving referenced history.

At-least-once publication and commit-before-manual-ACK consumption reuse the
existing three-attempt policy. `UNIQUE(source_event_id, recipient_user_id)` gives
an idempotent persistence effect under concurrent, duplicate and reordered
delivery. Replay never resets read state. This is not exactly-once transport or a
latency guarantee. A delayed alert remains a historical crossing after rearming.

All existing JWT-only `/api/v1/notifications` list, unread-count and mark-read
routes include quota alerts. Recipient scope precedes filters/count/page, and
successful responses retain `Cache-Control: no-store`. Existing pagination,
sorting, validation, monotonic read state and uniform foreign/unknown 404s apply.
`eventType=STORAGE_QUOTA_THRESHOLD_CROSSED` filters quota entries. Items add:

```json
{"eventType":"STORAGE_QUOTA_THRESHOLD_CROSSED","actionable":false,
 "resourceType":null,"resourceId":null,"resourceName":null,"newRole":null,
 "quota":{"threshold":80,"logicalUsageBytes":80,"limitBytes":100,
          "occurredAt":"2026-10-22T12:00:00Z"}}
```

Existing `id`, `sourceEventId`, `createdAt` and `readAt` remain; `createdAt` is
delivery persistence time and `quota.occurredAt` is event time. Grant entries
have `quota:null` and retain their current authorization/redaction behavior.
No actor/owner identity, private resource name, storage key or provider fact is
added. Alerts confer no upload/copy/resource authority. Fetch current owner usage
through `/api/v1/users/me/storage`; every mutation freshly checks authorization
and quota, irrespective of what a notification says.

## Operations and recovery

Deploy V44 with the updated publisher/consumer/API code together, stopping old
permission consumers before new quota events are published. Old consumers would
quarantine the new type. No mixed-version rollout is supported. Declare/verify
the existing permission topology before enabling the existing consumer; no VM
configuration change is required.

For an owner's discrepancy, inspect only exact bound owner/event UUIDs:

```sql
SELECT e.id, e.threshold, e.logical_usage_bytes, e.limit_bytes, e.occurred_at,
       o.delivery_status, o.last_failure_code, n.created_at, n.read_at
FROM storage_quota_events e LEFT JOIN outbox_events o ON o.id=e.id
LEFT JOIN sharing_notifications n ON n.source_event_id=e.id
    AND n.recipient_user_id=e.owner_id
WHERE e.owner_id=:owner_id ORDER BY e.occurred_at DESC,e.id DESC LIMIT 100;
```

Pending/failed publication uses [the existing publisher runbook](outbox-publishing.md).
Database exhaustion, uncertain ACK or unexpected failure stops consumption
unacknowledged; correct the cause and explicitly restart. Invalid messages commit
sanitized quarantine before ACK, using [the existing recovery procedure](sharing-m6.md).
Retain the original immutable source/outbox UUID, envelope and timestamps for
operator replay; do not fabricate another event, reset usage to force an alert,
or edit notification read state. A replay safely deduplicates already persisted
notifications. Broker loss after PUBLISHED or quarantined deliveries require
explicit operator replay; there is no automatic notification reconciliation.

Sources, outbox and notifications need retained history for validation/recovery.
Retention/account erasure and replay tooling remain undecided. Notification
publication does not change logical enforcement, physical storage uncertainty or
reclamation proof. Frontend/email/push, cooldown preferences, operational hardening,
performance/capacity claims and automatic uncertain-byte cleanup are outside this
milestone. Verification evidence is in [architecture](architecture.md#quota-notification-verification).

From `backend/`, reproduce focused checks with the private environment and
[existing isolated AMQP tunnel](async-purge-verification.md):

```powershell
. .\.env.local.ps1
$env:RABBITMQ_TEST_VHOST='vaultdrive_outbox_test'
.\mvnw.cmd -o '-Dtest=QuotaEventTest,QuotaNotificationIntegrationTest,StorageUsageIntegrationTest,SharingNotificationIntegrationTest,RabbitSharingNotificationIntegrationTest,FolderCopyIntegrationTest,FileCopyIntegrationTest,PermissionEventTest,PermissionEventListenerTest,PermissionConsumerConfigurationTest' test
```

Each database class selects its own fresh schema. Default execution switches
stay disabled. Full-suite gates, isolation and skipped prerequisites are recorded
with the [executed results](architecture.md#quota-notification-verification).
