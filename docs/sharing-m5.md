# Sharing M5: permission activity history

Implemented under the [approved sharing design](sharing-permissions-design.md).
M5 records durable activity for owners. Notifications, email, frontend work,
recipient feeds and inherited-descendant fan-out remain outside this milestone.

## Migration and immutable event contract

Append-only migration `V35__permission_activity.sql` creates
`permission_activity_events`:

| Fields | Meaning |
|---|---|
| `id`, `occurred_at` | UUID identity and PostgreSQL clock timestamp after coordination |
| `actor_id`, `actor_display_name`, `owner_id` | Acting owner identity and display-name snapshot |
| `resource_type`, `resource_id`, `resource_name` | FILE/FOLDER source identity and name snapshot; no ancestry |
| `event_type`, `subject_id`, `subject_version` | Changed grant/link identity and resulting revision |
| `recipient_id`, `recipient_email` | Direct recipient identity/email snapshot; null for links |
| `old_role`, `new_role` | Previous/new direct Viewer/Editor role; null where inapplicable |
| `link_expires_at` | Deadline snapshot for links; null for grants |

Types: `GRANT_CREATED`, `GRANT_ROLE_CHANGED`, `GRANT_REVOKED`, `LINK_CREATED`,
`LINK_REVOKED`. Creation uses revision zero. A grant update records its incremented
version; deletion records the last version plus one as the terminal revision.
Link revocation uses version one. Regrant/renewal receives a new subject UUID.
PostgreSQL enforces event-specific shapes, direct roles, finite timestamps,
acting-owner identity and unique `(subject_id, subject_version)`. A trigger rejects
UPDATE and DELETE. Owner/time/UUID and owner/resource/time/UUID indexes support
history queries. There is no backfill of pre-M5 changes.

No foreign keys cascade from resources, accounts, grants or links into history.
Snapshots survive metadata/account removal, rename, move and role changes.
No parent, breadcrumb, storage key, link secret/hash or signed URL is recorded.
Actor names and recipient emails are historical snapshots, not live display
lookups. Retention/account erasure policy remains undecided; there is no pruning
or administrative deletion API.

## Mutation, concurrency and retries

Order remains **exclusive resource-owner hierarchy -> fresh resource/ancestry/
permission/subject -> mutation -> activity insert -> outbox flush**. Only immutable
owner identity is read before locking. Mandatory activity/outbox writers share
the mutation's JPA transaction and connection. Insertion failure or later outer
rollback removes all three writes, even after flush. No network/storage I/O runs
inside mutation transactions. File versions/timestamps, storage allocations,
multipart evidence and existing lock ordering are unchanged.

One event describes the changed source. No-op role updates, duplicate/failed grant
creation, stale If-Match, unknown/wrong-target/repeated revocations and authorization/
lifecycle failures add no event. Role/revoke races snapshot the preceding committed
role/version. Retrying a rolled-back mutation records only the successful attempt;
stale role retries still fail their original precondition. Successful link creation has no idempotency key:
a fresh POST creates a fresh capability/event, as in M4. Automatic expiry is derived
and emits no event. Resource-deletion cascades do not synthesize revocation events.

## Authenticated API and visibility

`GET /api/v1/permission-activity` requires JWT and returns 200 with
`Cache-Control: no-store`:

```json
{"content":[],"page":0,"size":50,"totalElements":0,"totalPages":0}
```

Content uses the camelCase event fields: `id`, `actorId`, `actorDisplayName`,
`ownerId`, `resourceType`, `resourceId`, `resourceName`, `eventType`, `subjectId`,
`subjectVersion`, `recipientId`, `recipientEmail`, `oldRole`, `newRole`,
`linkExpiresAt`, `occurredAt`. Times are UTC ISO instants; inapplicable fields are null.

| Query parameter | Contract |
|---|---|
| `page`, `size` | Defaults 0/50; nonnegative page, size 1..100 |
| `resourceType` | Optional exact `FILE` or `FOLDER` |
| `resourceId` | Optional UUID, requires resourceType; exact source, no subtree expansion |
| `eventType` | Optional one of the five event types |

Filters combine with AND. Invalid pagination/UUIDs/enums or an untyped resourceId
return 400; missing/invalid JWT returns 401. Order is `occurred_at DESC, id DESC`.
Count and page use one PostgreSQL statement snapshot, including empty/out-of-range
pages and equal timestamps. Offset pages can shift between requests when events
arrive; this is not a frozen export. Query cost/throughput are unmeasured.

**Only the immutable event owner sees it**, including Trash, pending purge and
physically removed resources. SQL `owner_id = JWT subject` applies before filtering,
counting, sorting and pagination. There is no client owner selector. Viewer/Editor
status, affected-recipient identity and link possession confer no history visibility.
Other owners' and unknown resource IDs both return empty owner-scoped pages with
zero counts. A collaborator's feed may contain events for their own resources,
never the resources shared with them. No private ancestor or another owner's
recipient list enters a collaborator response.

History never supplies live resource authority. Ordinary APIs continue to resolve
current lifecycle/ancestry/grants. Revoked users cannot regain access through
historical activity. Restore reactivates only retained permissions under M1-M4;
pending purge freezes mutations while the owner retains history.

## Outbox and isolated routing

Each event atomically appends an outbox row with the same UUID/timestamp. Envelope:
`eventType=PERMISSION_ACTIVITY_RECORDED`, `aggregateType=PERMISSION_ACTIVITY`,
`aggregateId=<activity UUID>`, payload version 1,
`payload={"activityEventId":"<activity UUID>"}`. No display data or secret is
duplicated into the broker payload; the activity row is authoritative.

The publisher retains leased claiming, confirms/returns, bounded retry, fencing
and at-least-once crash replay. Retries reuse identity without appending history.
Permission events use the existing durable direct exchange and separate durable
quorum queue/key configured under `vaultdrive.outbox.publisher`:

| Property | Default |
|---|---|
| `permission-queue` | `vaultdrive.permission.activity` |
| `permission-routing-key` | `permission.activity` |

Configuration rejects a queue/key equal to the purge setting. Only the two purge
types use the purge route; unsupported types fail with `SERIALIZATION_ERROR`.
Purge missing-job scan/ingestion exclude permission events before pagination.
No permission consumer, notification, job or email is added. When publication is
enabled, messages accumulate on the permission queue for future M6 consumption.
All async defaults stay disabled. Deployment must preserve isolated bindings;
external broker rebindings remain an operator responsibility.

## Verification

`PermissionActivityIntegrationTest` covers all mutations, folder sources,
snapshots/roles/deadlines, file versions, no fan-out, rollback and database outbox
failure for every event type, uncommitted visibility, no-ops/retries/regrant,
immutability/constraints, rename/profile/removal, authenticated filters/counts,
recipient/stranger privacy, equal timestamps and lifecycle barriers.
`PermissionActivityConcurrencyIntegrationTest` observes PostgreSQL advisory waits
for duplicate creates, role/no-op/stale updates, role/revoke, repeated grant/link
revoke, rename ordering, grant/link creation versus ancestor Trash and revoke versus
purge in both orders.

Transport/configuration tests enforce isolation and unsupported-type rejection.
Live RabbitMQ replays after broker acceptance before DB confirmation: two messages,
one immutable activity, no purge message/job. Purge recovery excludes permission
events before scanning. Existing sharing/lifecycle/multipart/outbox/storage checks
remain regressions.

Load `backend/.env.local.ps1` in the Maven shell and select:

```text
-Dspring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=sharing_m5_verification
-Dspring.flyway.schemas=sharing_m5_verification
-Dspring.flyway.default-schema=sharing_m5_verification
```

Tests with their own isolated schemas retain them. The historical default-schema
V26 checksum mismatch is untouched. Cleanup targets UUID-scoped mutable fixtures;
immutable synthetic activity is retained. No shared schema or retained allocation
is swept. Broker checks use only `vaultdrive_outbox_test` through the documented
localhost SSH tunnel. Garage checks keep existing disposable-resource and
uncertainty-retention gates.

Executed on 2026-10-19 against PostgreSQL 17.11 and inspected Garage image
`dxflrs/garage:v2.3.0`:

- Focused selection `-Dtest=PermissionActivity*Test,RabbitOutbox*Test,OutboxPublisher*Test,MissingJobReconciliationIntegrationTest,ShareLink*Test,Sharing*Test`:
  **219 passed**, no failures/errors/skips.
- Full `mvnw.cmd test` with the schema arguments above and
  `RABBITMQ_TEST_VHOST=vaultdrive_outbox_test`: **1,319 tests, 1,271 passed,
  48 skipped**, no failures/errors. This includes PostgreSQL, Garage downloads/
  storage/purge, multipart lifecycle and eight isolated live RabbitMQ checks.
- Supplemental `-Dtest=GarageMultipartTransferIntegrationTest,GarageMultipartCompletionIntegrationTest,GarageMultipartCompletionEvidenceTest,GarageMultipartCancellationIntegrationTest,GarageMultipartPurgeIntegrationTest`:
  **27 passed**, no failures/errors/skips. It loads the private environment, uses
  the same schema arguments, sets the four existing
  `GARAGE_MULTIPART_{TRANSFER,COMPLETION,CANCELLATION,PURGE}_TEST=true` gates and
  `GARAGE_MULTIPART_VERSION=2.3.0`. These 27 checks were among the full-suite skips.
- `git diff --check` and local Markdown links passed.

The remaining **21 gated checks were not executed**:

| Suite | Skipped | Separate gate |
|---|---:|---|
| `AsyncPurgeEndToEndTest` | 1 | `ASYNC_E2E_TEST=true` |
| `AsyncPurgeProcessRecoveryTest` | 1 | `ASYNC_PROCESS_TEST=true` |
| `GarageMultipartCapabilityTest` | 18 | `GARAGE_MULTIPART_TEST=true` |
| `GarageMultipartJournalRecoveryTest` | 1 | reviewed `GARAGE_MULTIPART_REVIEWED_RUN` UUID |

Their process/capability/journal prerequisites remain separate. No browser/TLS/CORS,
clock-skew, late-writer or physical reclamation guarantee is added. Stop after M5;
M6 notifications and legacy upload retirement remain outside this task.
