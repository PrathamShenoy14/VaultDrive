# ADR-0008: Transactional outbox and durable asynchronous jobs

- Status: Accepted; development broker and purge-request outbox persistence implemented; publishing and workers planned
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
- Phase 1A provides development RabbitMQ infrastructure. Phase 1B implements PostgreSQL outbox persistence for new file/folder purge requests. Broker integration, publishing, durable jobs, workers, scheduler, retries, and recovery remain unimplemented.

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

Planned delivery is at least once: only committed outbox rows may be published. If RabbitMQ accepts a notification and the publisher crashes before recording `PUBLISHED`, a retry may publish the same UUID again. Consumers must tolerate duplicate publication and redelivery, use the event UUID for notification deduplication where useful, and conditionally claim/advance PostgreSQL durable jobs idempotently. Deduplication alone must not suppress recovery of unfinished work. Broker acknowledgement is not a business-state commit; the outbox is not a durable purge job table.

Existing pending-purge requests predating V12 are not backfilled by this migration. Recovery/backfill, event retention, publisher claiming, operational retry bookkeeping, and handling of unknown versions remain future bounded tasks. Phase 1B creates no queue/exchange, broker operation, listener, scheduler, Garage deletion, or worker.

## Deferred decisions

Queue and exchange names, routing, durable-job schemas and states, claim and fencing protocol, lease duration, retry/backoff counts, terminal-failure handling, retention, ordering, publisher batching, and operational thresholds remain open for bounded implementation work. The outbox columns and version-1 purge intent payloads above are decided for Phase 1B.
