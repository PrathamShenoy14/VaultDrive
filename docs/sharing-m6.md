# Sharing M6: in-app sharing notifications

M6 implements the final milestone of the [approved sharing design](sharing-permissions-design.md).
Only the direct registered recipient receives `GRANT_CREATED`, `GRANT_ROLE_CHANGED`
and `GRANT_REVOKED`. Owners receive no notification for their own actions. Link
events are validated and acknowledged without a notification. There is no
inherited-descendant fan-out, email, push, WebSocket or frontend work.

## Persistence and delivery

Append-only migration `V36__sharing_notifications.sql` adds `sharing_notifications`:
UUID `id`, immutable `source_event_id` referencing M5 activity, immutable
`recipient_user_id`, `event_type`, `created_at`, and nullable `read_at`.
Creation time is database delivery-persistence time, rather than event occurrence
time. PostgreSQL validates recipient/type against the immutable source and rejects
identity changes. Read state advances once from null to a finite timestamp and
cannot be reset or rewritten. `UNIQUE(source_event_id, recipient_user_id)` is the
concurrent-consumer/duplicate-delivery guard. Replays never reset read state.
Recipient/time/UUID and partial unread indexes support the APIs.

Notification identities deliberately have no account/resource/grant cascade.
Activity and notifications survive resource/account removal. No display snapshots
are copied into notifications: M5 activity is authoritative. There is no pruning,
account-erasure policy or deletion API; retention remains an open operational
decision. Pre-M5 mutations are not backfilled.

M5 permission mutation/activity/outbox atomicity is unchanged. Delivery is a
separate short transaction and never participates in a permission mutation.
`PERMISSION_ACTIVITY_RECORDED` payload v1 remains an `activityEventId` pointer.
The listener validates strict JSON (including duplicate/trailing-token rejection),
canonical UUIDs, message identity/type, version, aggregate and timestamp against
the committed outbox, then reads immutable activity. It does not require outbox
`PUBLISHED`: delivery may precede the publisher's completion commit.

Notification insertion commits before manual ACK. Duplicated publication,
redelivery, competing consumers and commit-before-ACK crashes produce one row per
event/recipient. Arrival order is unrestricted. Link events are deliberately
non-deliverable. No purge job, storage call, hierarchy lock or network I/O enters
the delivery transaction. Permission mutations acquire exactly their existing
locks and never wait for broker delivery.

## Authenticated API

All routes require JWT and return `Cache-Control: no-store` on successful responses.
Recipient identity always comes from the JWT subject; there is no recipient selector.

| Route | Contract |
|---|---|
| `GET /api/v1/notifications` | `200` page with `content`, `page`, `size`, `totalElements`, `totalPages` |
| `GET /api/v1/notifications/unread-count` | `200 {"unreadCount":0}` |
| `PATCH /api/v1/notifications/{id}/read` | Idempotent `204`; unknown and other recipients' IDs both `404` |

List parameters: `page` defaults 0 and must be nonnegative; `size` defaults 50,
range 1..100. Optional `unread=true` selects unread, `false` selects read, omitted
selects both. Optional `eventType` selects exactly one of the three grant types.
Invalid enums, UUIDs, booleans and pagination return `400`; missing/invalid JWT
returns `401`. The API has no mark-unread or bulk state-changing operation.

SQL recipient scope applies before optional filters, counts, sorting and offset.
Count, page and resource display authorization share one PostgreSQL statement
snapshot, even for empty/out-of-range pages. Order is `created_at DESC, id DESC`.
Pages may shift between requests as delivery/read state changes; this is not a
frozen export. Throughput and query cost are unmeasured.

Each item contains `id`, `sourceEventId`, `eventType`, `createdAt`, `readAt`,
`actionable`, `resourceType`, `resourceId`, `resourceName`, `newRole`. Times are UTC
ISO instants. Resource fields and new role are null unless **both** conditions hold:

- The same direct grant still exists at exactly the event's subject UUID and
  revision, with the event's role and recipient.
- Current permission resolution permits access through current lifecycle and
  complete ancestry.

Eligible display uses the event's name/role snapshot. It includes no actor name,
owner/recipient identity or email, old role, grant identity, ancestry, storage key,
link secret or signed URL. A rename does not rewrite the source name snapshot.
Later role changes, revoke/regrant, Trash, pending purge and physical deletion
make affected history generic (`actionable=false`, null resource fields/role).
Revocation is always generic, even if another inherited grant still permits
access. Obsolete events remain in history/counts and may be marked read; delayed
delivery cannot turn an obsolete event back into an action.

A notification is informational. Clients must open through ordinary resource
APIs, which recheck current grants, ancestry and lifecycle. Access changes after
the list statement may race with response delivery; no notification contains a
resource capability or bypasses a later authorization check.

## Operations and failure recovery

`vaultdrive.sharing.notifications.consumer.enabled` defaults false, mapped from
`SHARING_NOTIFICATIONS_CONSUMER_ENABLED=false`. The test profile also forces false.
Publisher, permission consumer, purge consumer, job worker and reconciliation
remain independent switches. Do not enable shared development workers to run tests.

The consumer uses the existing publisher `permission-queue` setting (default
`vaultdrive.permission.activity`) and existing `permission-routing-key`
(`permission.activity`), separate from purge. Configuration rejects route
collisions. It consumes existing topology only: durable quorum queue declaration
and binding remain the publisher's responsibility. Declare/verify the permission
topology before enabling this consumer. There is one manual-ACK consumer per
instance, prefetch 1, no channel transaction, and no new exchange, DLX or worker
framework. Multiple application instances remain safe through database uniqueness.

Database/commit failures get three total attempts, with one- then two-second
backoff outside transactions. Exhaustion, unexpected failure, interruption or
uncertain ACK stops the container with unacknowledged work. There is no immediate
reject/requeue loop and no automatic endless retry. Monitor consumer/container
health and sanitized stop codes; fix the cause and explicitly restart to allow
broker redelivery. A stopped listener can leave the API process running.

Invalid, unsupported, unknown-source or mismatched messages first commit a
sanitized fingerprint/event-ID/reason in existing `notification_quarantine`, then
ACK. No body, header, secret or exception text is retained. Failed quarantine
persistence also exhausts to stop/unacked. Quarantine is retained evidence, not
successful notification delivery. Inspect controlled reason codes, correct the
source/problem and explicitly replay the original immutable envelope after
operator review; no recovery API or automatic quarantine replay exists.
Outbox/activity retention is required for validation. Never fabricate an event or
change its identity to recover a delivery. Published events whose broker delivery
is lost require operator replay; no notification reconciliation scanner is added.
Publisher exhaustion/rearm follows [the existing runbook](outbox-publishing.md).

At least once describes transport, while uniqueness provides an idempotent
persistence effect. It is not an exactly-once broker or guaranteed-latency claim.
Single-node broker durability does not establish multi-node availability.

## Verification

Tests use `sharing_m6_test` and `sharing_m6_rabbit_test` schemas in
`vaultdrive_test`. They retain synthetic immutable activity/notifications and
remove only their own mutable UUID fixtures. RabbitMQ checks require
`RABBITMQ_TEST_VHOST=vaultdrive_outbox_test` and the documented localhost SSH
tunnel, create UUID-named topology, and remove only that topology. Shared schemas,
retained storage allocations and the default vhost are never swept.

Load the ignored local environment in the Maven shell. Focused selection:

```powershell
. .\.env.local.ps1
$env:RABBITMQ_TEST_VHOST='vaultdrive_outbox_test'
.\mvnw.cmd '-Dtest=SharingNotificationIntegrationTest,PermissionEvent*Test,PermissionConsumerConfigurationTest,RabbitSharingNotificationIntegrationTest' test
```

The full suite uses a separately migrated schema via:

```text
-Dspring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=sharing_m6_verification
-Dspring.flyway.schemas=sharing_m6_verification
-Dspring.flyway.default-schema=sharing_m6_verification
```

Executed on 2026-10-19 against PostgreSQL 17.11, RabbitMQ 4.3.6 and inspected
Garage image `dxflrs/garage:v2.3.0`:

- Focused selection above: **40 passed**, no failures/errors/skips, including 15
  PostgreSQL/API checks, four live RabbitMQ checks, 15 parser cases, five listener
  failure/transaction checks and the disabled-default configuration check.
- Initial full baseline: **1,353 tests, 1,305 passed, 48 skipped**, no
  failures/errors. Six subsequently added checks passed in the focused run.
- The first live attempt found the localhost AMQP tunnel closed (connection
  refused); opening the task-owned SSH tunnel resolved all four broker checks.

The final full run uses the schema arguments above, the RabbitMQ vhost gate and
these existing safe Garage gates in the same private-environment Maven shell:

```powershell
$env:GARAGE_MULTIPART_TRANSFER_TEST='true'
$env:GARAGE_MULTIPART_COMPLETION_TEST='true'
$env:GARAGE_MULTIPART_CANCELLATION_TEST='true'
$env:GARAGE_MULTIPART_PURGE_TEST='true'
$env:GARAGE_MULTIPART_VERSION='2.3.0'
```

Final full result: **1,359 tests, 1,338 passed, 21 skipped**, no failures/errors.
This includes all 40 M6 checks, 12 isolated live RabbitMQ checks and the 27 gated
Garage multipart checks. `git diff --check`, staged whitespace checks, local
Markdown links and sanitized environment-example PowerShell parsing passed.

The following existing checks were deliberately not enabled:

| Suite | Skipped | Separate gate |
|---|---:|---|
| `AsyncPurgeEndToEndTest` | 1 | `ASYNC_E2E_TEST=true` |
| `AsyncPurgeProcessRecoveryTest` | 1 | `ASYNC_PROCESS_TEST=true` |
| `GarageMultipartCapabilityTest` | 18 | `GARAGE_MULTIPART_TEST=true` |
| `GarageMultipartJournalRecoveryTest` | 1 | reviewed `GARAGE_MULTIPART_REVIEWED_RUN` UUID |

Their separate process, capability and retained-journal prerequisites remain
unchanged. No shared schema or retained allocation was swept. Test-owned mutable
fixtures/topology were removed and the task-owned AMQP tunnel was closed.

Existing historical V26 checksums remain untouched. M6 ends here; legacy
synchronous upload retirement, retention/account erasure, browser release
prerequisites and frontend remain outside this change.
