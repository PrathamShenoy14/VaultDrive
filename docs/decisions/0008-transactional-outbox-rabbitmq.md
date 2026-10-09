# ADR-0008: Transactional outbox and durable asynchronous jobs

- Status: Accepted; broker infrastructure, outbox persistence and leased publishing implemented; workers and durable jobs planned
- Date: 2026-10-09

## Context

Purge, reconciliation, and other background work must survive process restarts and failures across PostgreSQL, RabbitMQ, and Garage. Publishing directly from an API transaction can lose a notification after commit or publish work for a transaction that later rolls back. RabbitMQ delivery can be duplicated or missed, and broker state alone is not sufficient as VaultDrive's durable record of job progress.

ADR-0006 left database polling versus a broker/outbox open. This ADR resolves that topology decision without defining its implementation schema or operational tuning.

## Decision

Use a PostgreSQL transactional outbox with RabbitMQ notifications and PostgreSQL-backed durable job state. PostgreSQL remains authoritative for business metadata, outbox events, job state, and recovery checkpoints.

File and folder permanent-delete request transactions atomically persist the business-state change and corresponding outbox event. A separate publisher will deliver committed outbox events to RabbitMQ and tolerate duplicate publication. RabbitMQ will notify workers that work is available; it will not be the sole durable job record.

Workers will claim durable jobs in PostgreSQL and process them idempotently. A scheduler will scan PostgreSQL to publish missed outbox events, recover missed notifications or abandoned work, and drive bounded retries. Database polling is therefore a recovery and publishing mechanism within the RabbitMQ design, not an alternative worker topology.

Garage/S3 calls will remain outside metadata database transactions. Workers will persist claims or conditional state transitions in short transactions, perform object-storage I/O separately, and then conditionally record known outcomes. Duplicate delivery, worker crashes, and uncertain storage outcomes must remain safe to retry.

## Consequences

- A committed business transition and its event cannot diverge at the API transaction boundary.
- RabbitMQ reduces notification latency while PostgreSQL preserves durable truth and enables recovery when publication or delivery is missed.
- Publishers and consumers must assume at-least-once effects and be idempotent; exactly-once delivery is not required.
- Purge and reconciliation workers must preserve the lifecycle, hierarchy, ownership, and lock-order invariants in ADRs 0005–0007.
- The earlier polling-first possibility in ADR-0006 is superseded by this decision.
- Phase 1A provides development RabbitMQ infrastructure; Phase 1B provides atomic purge-request outbox persistence; Phase 1C provides leased publication, confirms, bounded retries, and expired-claim recovery. Durable jobs, consumers, workers, job recovery, and physical deletion remain unimplemented.

## Phase 1B schema and transaction decision

Migration `V12__create_outbox_events.sql` creates `outbox_events` using the existing Flyway SQL naming convention. Events have application-generated UUID primary keys, `event_type`, `aggregate_type`, `aggregate_id`, positive `payload_version`, JSONB object `payload`, and `created_at`. Immutable envelope/payload fields use JPA mappings and the existing Spring Data JPA transaction manager; no second persistence framework is introduced.

Aggregate references deliberately have no foreign keys to files, folders, or users. The event must survive future physical deletion of its aggregate, and cannot cascade away with business metadata. This does not authorize a consumer to trust stale payload state: future consumers must reload owned durable state and apply lifecycle/claim checks. Event UUID identifies a notification; aggregate UUID identifies the business resource. No generic uniqueness constraint forbids multiple legitimate events for one aggregate. Current purge eligibility and locking allow only one accepted purge intent per resource; failed or repeated requests add no event.

Delivery bookkeeping is `delivery_status` (`PENDING` or `PUBLISHED`), nonnegative `attempt_count`, nullable `last_attempt_at`, required `next_attempt_at`, and nullable `published_at`. New rows are `PENDING`, have zero attempts, no attempt/publication timestamp, and become eligible at their creation timestamp (the purge-request timestamp). PostgreSQL enforces nonblank event/aggregate types, positive payload versions, JSON object payloads, valid delivery states, and publication timestamp consistency. `PUBLISHED` will mean notification publication acknowledged by the broker, not completion of physical purge. No terminal-failure state or retry limit is selected here.

The partial index `(next_attempt_at, created_at, id) WHERE delivery_status = 'PENDING'` supports bounded due-event scans with stable tie-breaking. The aggregate index `(aggregate_type, aggregate_id, created_at)` supports resource/event lookup. These indexes prepare for concurrent publishers; they do not implement safe claiming, leases, or ordering guarantees. A later publisher must choose a crash-safe claim/fencing protocol using short PostgreSQL transactions; it must not hold database transactions or hierarchy locks across broker I/O. `FOR UPDATE SKIP LOCKED` may help a future claim scan, but alone does not solve broker acknowledgement and crash recovery.

`OutboxWriter.append` requires an existing Spring transaction (`Propagation.MANDATORY`) and uses `saveAndFlush`. Both purge services flush the validated business transition, append the event, and then commit the same JPA transaction. Flush is not commit: insert failure or later outer rollback removes both writes. Outbox errors propagate; no successful `202 Accepted` is returned when insertion fails. Lock order remains owner hierarchy lock (shared for file, exclusive for folder), fresh eligible resource-row lock, ancestry/subtree checks, business flush, then insertion of a new outbox row. No existing outbox rows are locked by the request path. Ownership, eligibility errors, and successful API responses remain unchanged.

## Event versioning and future delivery contract

| Event type | Aggregate type | Payload version | JSON payload fields |
|---|---|---|---|
| `FILE_PURGE_REQUESTED` | `FILE` | `1` | `ownerId`, `fileId` (UUID strings) |
| `FOLDER_PURGE_REQUESTED` | `FOLDER` | `1` | `ownerId`, `folderId` (UUID strings) |

`payload_version` versions the payload independently of database migration numbering and must travel with the future published envelope along with event UUID, event type, aggregate reference, and creation timestamp. Version 1 describes purge intent only; it includes no bytes, credentials, names, storage keys, subtree snapshot, or worker instructions. A folder event represents the requested root, not one event per descendant. Breaking payload changes require a new payload version and explicit consumer support; never reinterpret or rewrite existing events in place. Future consumers must validate event type/version and resource identity, tolerate compatible additional fields, and leave unsupported versions for explicit failure/recovery handling rather than acknowledge them as completed work.

The delivery contract is at least once: only committed outbox rows may be published. If RabbitMQ accepts a notification and the publisher crashes before recording `PUBLISHED`, a retry may publish the same UUID again. Future consumers must tolerate duplicate publication and redelivery, use the event UUID for notification deduplication where useful, and conditionally claim/advance PostgreSQL durable jobs idempotently. Deduplication alone must not suppress recovery of unfinished work. Broker acknowledgement is not a business-state commit; the outbox is not a durable purge job table.

Existing pending-purge requests predating V12 are not backfilled by this migration. Recovery/backfill, event retention, and consumer handling of unknown versions remain future tasks. Phase 1B added no broker execution; Phase 1C below supplies publisher claiming and retry bookkeeping while leaving consumers, Garage deletion, and workers unimplemented.

## Deferred decisions

Durable-job schemas and states, worker claim/fencing, job recovery, event retention, historical-intent backfill, ordering guarantees, and operational thresholds remain open. The Phase 1B event contract remains unchanged. Publisher-specific choices are recorded below; they do not define purge-worker claiming.

## Phase 1C publisher protocol

V13 adds `claim_token`, `claim_expires_at`, and sanitized `last_failure_code` to the outbox, plus `PUBLISHING` and `FAILED` delivery states. PostgreSQL enforces that only a `PUBLISHING` row has a complete token/expiration pair, and only `PUBLISHED` has a publication timestamp. A partial expiration index complements the V12 pending-delivery index. Applied migrations are unchanged.

Each instance claims one due event at a time in a short, independent JPA transaction: select an eligible `PENDING` row, or an expired `PUBLISHING` row whose retry time is due, using `FOR UPDATE SKIP LOCKED`; generate a fresh UUID claim token; increment the attempt counter; set attempt time, lease expiration, and the abandoned-attempt retry deadline from PostgreSQL's clock; flush and commit before returning an immutable snapshot. The publisher only locks outbox rows and does not acquire hierarchy/resource locks. It rejects an enclosing database transaction, and the transport independently rejects database transactions before broker I/O.

Publication runs after claim commit. Completion uses a separate conditional update matching event UUID, `PUBLISHING`, the exact token, and an unexpired lease. Both confirmed success and known failure are fenced this way. A late claimant cannot overwrite a newer claim or complete its expired lease. PostgreSQL's clock governs ownership and deadlines, avoiding publisher-host clock skew. A pre-send ownership check avoids sending claims already known to be lost; expiry immediately after that check can still overlap broker sends, so database fencing does not promise exclusive broker delivery.

Defaults are configurable: 30-second lease, 10-second confirm wait, 10 total attempts including abandoned attempts, one-second initial exponential backoff capped at 60 seconds, five-second poll delay, and at most 20 publication attempts per poll. A lease must exceed the confirmation timeout. Network connection/RPC time and scheduling delays also consume the lease; tune it to the environment rather than assume a total network deadline. One-at-a-time claiming prevents a local batch waiting in memory from consuming its leases before sending. Exhausted expired rows are handled in bounded scans.

Known negative confirms, returns, timeouts, serialization errors, interruptions, and connection failures release the current lease to `PENDING` with a database-clock retry deadline, or to `FAILED` when the attempt budget is exhausted. On process death or failure to record completion, the committed lease remains. Its retry deadline is lease expiration plus that attempt's backoff; after both deadlines pass, another publisher can reclaim it with a new token. An abandoned final attempt becomes `FAILED`. Failed rows retain their immutable event/payload and stop automatic attempts; operator-approved recovery is required. There is no raw exception storage or logging of connection URLs/credentials.

Topology defaults: durable direct exchange `vaultdrive.outbox.events`, durable non-exclusive, non-auto-delete quorum queue `vaultdrive.purge.requests`, binding key `purge.requested`. Both purge intent types use that binding and retain their event type in the envelope. Messages use persistent delivery mode and the immutable event UUID as AMQP `message_id` and JSON `eventId`. The complete envelope carries event/aggregate type and UUID, payload version, creation timestamp, and JSON payload. Attempt tokens are internal confirmation correlation identifiers, not consumer deduplication IDs.

Spring AMQP correlated confirms and publisher returns are required, with mandatory publication enabled. A successful send call alone is insufficient: only a positive confirm with no returned message permits `PUBLISHED`. An unroutable mandatory message can be returned and positively confirmed by RabbitMQ; it must remain retryable. Spring populates returned-message information before completing the correlated confirmation future ([Spring AMQP contract](https://docs.spring.io/spring-amqp/reference/amqp/template.html)). Topology is declared idempotently outside database transactions; failed declarations are retryable. A quorum queue on this single-node development broker is durable but provides no multi-node availability.

The broker-acceptance/PostgreSQL-recording crash window is intentionally at least once: a confirmed notification whose completion was not recorded will be replayed with the same event UUID. Confirmed publication means broker acceptance/routing, never completed deletion. Consumers must still deduplicate or conditionally advance authoritative PostgreSQL work idempotently. No consumer, purge worker, Garage call, or historical-intent backfill is added.

Automatic polling is opt-in (`OUTBOX_PUBLISHER_ENABLED=true`) and disabled in the test profile. Tests invoke the production claiming/orchestration/transport explicitly using isolated database fixtures and, when approved, the `vaultdrive_outbox_test` RabbitMQ vhost. See [publisher development and recovery instructions](../outbox-publishing.md).
