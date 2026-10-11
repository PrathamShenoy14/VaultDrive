# ADR-0008: Transactional outbox and durable asynchronous jobs

- Status: Accepted; broker/outbox/job/consumer/worker foundation and trusted-writer file byte purge implemented; folder purge and metadata finalization planned
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
- Phase 1A provides development RabbitMQ infrastructure; Phase 1B provides atomic purge-request outbox persistence; Phase 1C provides leased publication, confirms, bounded retries, and expired-claim recovery; Phase 2A provides job persistence and fenced lifecycle/recovery primitives; Phase 2B provides validated notification ingestion with commit-before-ACK and sanitized quarantine; Phase 2C provides bounded worker dispatch, lease renewal and scheduled recovery for registered handler types. Phase 2D.1 adds trusted-writer file byte purge. Folder purge, metadata finalization and missed-job backfill remain unimplemented.

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

Existing pending-purge requests predating V12 are not backfilled by this migration. Recovery/backfill and event retention remain future tasks. Phase 1B added no broker execution; Phase 1C below supplies publication only. Phase 2B separately handles notification ingestion and quarantines unknown versions; Phase 2D.1 below supplies file byte deletion without changing v1 events.

## Deferred decisions

Folder purge execution, metadata finalization, missed-notification/job backfill, outbox/job/quarantine retention, ordering guarantees and operational tuning remain open. The Phase 1B event contract remains unchanged. Publisher, job, consumer, worker foundation and Phase 2D.1 file-purge choices are recorded separately below.

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

### Phase 1C outbound-I/O hardening

The confirmation deadline starts after `RabbitTemplate.send`, so it cannot bound blocking socket writes. Enabled publisher configuration now uses the bundled Java client's non-blocking NIO transport, a bounded 128-frame queue and one-second per-frame enqueue timeout. This removes the blocking socket-write path instead of abandoning a stuck executor future. Queue saturation is a retryable I/O failure; enqueued frames can still reach the broker after an uncertain timeout, preserving the same at-least-once/fencing contract. No database transaction, outbox schema, topology, or payload changes.

One-thread bounded I/O and separate shutdown executors prevent unbounded worker growth. The single fixed-delay scheduler interrupts in-flight work on shutdown, connections have a one-second close-handshake timeout, and executor termination waits are finite. Defaults apply to the current small intent notifications, not an absolute total-event or application-shutdown deadline. NIO is deprecated in this client; using it avoids adding Netty dependencies in this focused fix. A future client upgrade must retain equivalent non-blocking, bounded backpressure and shutdown semantics. See the runbook for exact capacities, timeout composition, and test limitations.

## Phase 2A durable job foundation

V14 creates `background_jobs` separately from the outbox. The job ID is the stable event UUID, enforced by the primary key, with one logical job per event. `ON CONFLICT DO NOTHING` creation joins the caller transaction, validates duplicate type/aggregate identity, and never resets existing work. Aggregate/event references have no foreign keys so jobs and deduplication survive metadata/outbox retention. Terminal job retention must preserve that identity. Job types are initially `FILE_PURGE` and `FOLDER_PURGE`; no payload is stored. Future consumers must validate event versions and business eligibility before creation/execution.

The lifecycle is `PENDING -> RUNNING -> SUCCEEDED`, with retry to `PENDING` and terminal `FAILED` at exhaustion. PostgreSQL enforces valid type/aggregate combinations, statuses, attempt bounds, complete claim pairs only for `RUNNING`, attempt timestamps, terminal timestamps, and controlled failure codes. Partial due/expired indexes and an aggregate lookup index support scans. Creation, claim, outcome and terminal timestamps remain durable and use database time.

Claims use independent short JPA transactions with ordered job-row `FOR UPDATE SKIP LOCKED`, a fresh UUID token, a 30-second lease, and an incremented attempt. Ten total attempts are persisted per job. Backoff starts at one second, doubles per attempt, and caps at 60 seconds. Known failure schedules from database time when recorded; each claim pre-records a crash retry deadline of lease expiration plus backoff. Both deadlines must pass for abandoned-claim recovery. These fixed defaults are a foundation policy, not measured operational tuning or a long-operation lease design.

Completion/retry lock the job row before reading protected state and checking the token and unexpired lease with PostgreSQL's clock, then apply a fenced conditional update. Persisted counters determine retry/exhaustion; caller snapshot counters are not trusted. Only job rows are locked, with no hierarchy/resource locks. Claims commit before returning immutable snapshots. Future orchestration must have no enclosing transaction during external I/O; `REQUIRES_NEW` alone does not close an outer transaction. Existing APIs, purge intent, outbox and storage boundaries are unchanged.

`claimNext(type)` also recovers expired due jobs with new tokens, or marks abandoned final attempts `FAILED`. It examines at most 20 candidates per call and returns at most one executable claim. Future polling must repeat scans even after an empty result that cleared exhausted rows. Terminal rows stop automatic attempts and retain identity/reference. No automatic reset, renewal, consumer, scheduler, historical-intent backfill, checkpoints, or worker execution is added. Recovery of notifications that never created jobs remains a later phase.

Database fencing cannot cancel stale external I/O. A crash after an external effect but before outcome commit can repeat that effect; future executors still require idempotence and upload writer fencing. Tests cover concurrent claims/creation, rollback, skip-locked/released locks, stale/expired outcomes including lock waits, backoff and both exhaustion paths against real PostgreSQL. See [the job state machine and recovery contract](../background-jobs.md).

## Phase 2B notification ingestion and quarantine

An opt-in Spring AMQP container consumes the existing publisher queue with manual acknowledgement, one consumer and prefetch one per instance. It declares no topology and changes no existing broker queue arguments or policies. Startup is disabled by default with `vaultdrive.purge.consumer.enabled=false`; deployment enablement is separate from repository verification. No Garage I/O, job execution, hierarchy locking or file/folder mutation occurs in ingestion.

Validation accepts the existing v1 file/folder envelope only: strict JSON object parsing, integer payload version, canonical event/aggregate/owner/resource UUIDs, matching AMQP message ID (and type if present), type/aggregate/payload consistency and a valid creation timestamp. Duplicate JSON keys, trailing tokens and bodies over 64 KiB are rejected. Compatible extra fields are ignored. In an independent short JPA transaction, the consumer matches required fields against the committed immutable outbox event, then creates/finds the job with the event UUID. It does not require `PUBLISHED`, since notification delivery can precede publisher outcome commit. Outbox read precedes job insertion; no hierarchy/resource locks or external I/O enter that transaction.

The transactional ingestion proxy must return after commit before `basicAck(tag, false)` runs outside every database transaction. A listener invoked inside an enclosing transaction is rejected. Flush or an apparently successful insert is not sufficient for ACK. Duplicate deliveries preserve all job progress, including terminal state. Rollback/commit failure leaves the delivery unacknowledged; commit followed by a lost ACK leaves the same job for idempotent broker redelivery. No distributed/channel transaction or exactly-once claim is made.

Permanently invalid deliveries are durably quarantined in PostgreSQL before ACK instead of adding a RabbitMQ DLX. V15 adds a ledger keyed by SHA-256 body fingerprint, with nullable parsed event UUID, a controlled reason and database creation time. The first invalid disposition is retained idempotently. Raw bodies/headers and exception text are not stored or logged; this intentionally sacrifices recovery of arbitrary invalid bytes. Unsupported versions/types, unknown/mismatched outbox events and conflicting job identities create no new job. A later corrected replay is not suppressed by the quarantine ledger.

Database access/transaction failures retry at most three times per broker delivery, using separate transactions and one-/two-second delays outside transactions. Exhaustion stops the container with a sanitized fatal error, leaving the delivery unacknowledged; channel closure permits broker redelivery after an explicit restart. The same rule protects quarantine writes during an outage. Interruptions, unexpected errors and uncertain ACKs also stop consumption. There is no immediate reject/requeue hot loop, automatic database-recovery restart, or durable cross-restart ingestion attempt counter. Operators must not add a rapid process restart loop. Framework connection recovery remains separate from this database retry budget.

This PostgreSQL quarantine/stop policy preserves existing RabbitMQ topology and keeps durable disposition in PostgreSQL. Its trade-offs are head-of-line pausing during a sustained database outage, reset retry budgets on explicit restart, and no raw invalid-message recovery. Outbox records must be retained for inbound validation/replay; deleting a source early quarantines even a duplicate as `UNKNOWN_EVENT`. Retention, replay tooling and recovery of notifications/jobs that never arrived remain future work.

PostgreSQL tests establish commit-before-ACK, commit failure rollback, duplicate/terminal replay, quarantine and its constraints. Live isolated-vhost tests exercise publisher-to-consumer delivery, malformed/unsupported messages, bounded failure/stop, restart/redelivery and commit-before-ACK loss. They use UUID-suffixed test topology only. See [consumer configuration and operational recovery](../purge-consumer.md). No execution worker, reconciliation, storage deletion, or running development API deployment is added.

## Phase 2C worker scheduling, renewal and shutdown

The worker foundation is opt-in and disabled by default (`vaultdrive.jobs.worker.enabled=false`). Two daemon fixed-delay control loops schedule claims and inspect active work without control task queues. Handler and renewal pools each have a fixed maximum `N` threads, a bounded `N`-entry queue and rejection policy. A semaphore reserves a slot before claiming; local executing/handoff claims never exceed `N`. Automatic renewals are coalesced per claim and isolated from monitoring so slow database calls cannot block all local deadline checks. Defaults are two worker slots, five-second polling and ten-second renewal scans, with concurrency bounded to 1–32.

Only registered `JobHandler` types are claimed, in round-robin order. No production purge handler is registered in this slice. Missing handlers leave jobs/attempts untouched rather than pretending work succeeded. Claim/recovery uses the existing short PostgreSQL transaction and commits before handler dispatch. The worker rejects enclosing transactions, renews/checks ownership before invoking a handler, and requires explicit `SUCCEEDED` or `RETRY` outcomes. Null and ordinary exception outcomes retry; cancellation, interruption, timeout and uncertain outcome recording leave the lease recoverable. No hierarchy/resource mutation or storage I/O is introduced.

Renewal locks the job row before reading protected state, checks the token and unexpired lease using PostgreSQL's clock, and conditionally writes with the same predicate. The expiration is never shortened and becomes at least database time plus 30 seconds. The abandoned-attempt retry deadline moves to renewed expiration plus persisted attempt backoff; attempts and token remain unchanged. Expired/replaced tokens cannot renew. No migration is needed and applied V14/V15 migrations are unchanged.

The five-minute default max runtime limits perpetual renewal. A conservative monotonic local lease window counts from before the successfully confirmed renewal call, including database call/commit delays, rather than granting a fresh window when it returns. Renewal failure/uncertainty or elapsed local lease/runtime cancels work and requests interruption; no cancellation frees capacity until the actual task returns. Handler/thread attachment and detachment synchronize cancellation so a late renewal cannot interrupt a subsequent task on a reused pool thread. Database fencing still cannot cancel external effects, and future handlers need idempotence, lifecycle validation and upload writer fencing.

Context closure immediately stops new claim admission. The worker allows active completion/renewal within one default five-second grace budget (configurable up to 60 seconds), then stops monitoring and interrupts unfinished handlers, renewals and control loops. All waiting shares that deadline. Unresponsive daemon threads can outlive this wait; no replacement pool bypasses stuck capacity. In-flight claims or renewals may commit after shutdown begins, so later recovery uses final persisted deadlines. Legitimately finished outcome transactions already begun may commit. Other unfinished work is not forcibly completed or immediately rearmed.

Supported-type polling recovers missed execution wakeups and expired attempts through the existing claim scan/backoff/exhaustion protocol. It does not materialize jobs missing from lost notifications or historical intent. Tests use real PostgreSQL for concurrent schedulers, renewal/lock-wait fencing, capacity, crash/timeout recovery, slow renewal isolation and shutdown; Spring context closure is also exercised. No physical purge handler or process-kill/load experiment is added. See [worker configuration and failure scenarios](../job-workers.md).

## Phase 2D.1 file byte purge and trusted-writer decision

Retain Garage 2.3.0 and explicitly accept trusted storage writers. Garage's S3
boundary does not enforce write-once keys or generation-specific DELETE. All
authorized writers/operators must honor permanent nonreuse, no replacement of
finalized content, and stable storage namespace/endpoint/bucket bindings. An
out-of-band overwrite or alias/endpoint reassignment can make a replayed DELETE
remove replacement bytes. This is accepted risk, not a guarantee from UUIDs,
metadata versions, ETags or leases. No gateway/provider migration is introduced.

V16 adds `file_storage_objects` retained key allocations and `file_purge_targets`
immutable event-ID deletion targets. New UPLOADING reservations capture a logical
namespace (`S3_NAMESPACE`, default `garage-default`), endpoint, region and bucket.
A short shared-hierarchy/eligible-file-row/allocation-row transaction consumes
one storage write invocation before PUT. Allocations survive file removal; primary
key/unique constraints and monotonic identity triggers prohibit intentional key
reuse. SDK retries are disabled to avoid overlapping uncertain upload attempts.
Migration reserves legacy keys as consumed without inventing original bindings.

For a new verified file, pending-purge flush, immutable target insertion and v1
outbox append share one PostgreSQL transaction. Target identity includes the file
version/purge timestamp plus exact upload binding/key. The event payload and
notification/job protocols remain unchanged; separate target rows have no
cascading foreign keys. Legacy requests lacking a verified target remain accepted
by the API but fail closed during execution; no implicit backfill is performed.

The FILE_PURGE handler reads its immutable event-ID target, acquires the shared
owner hierarchy lock, freshly locks/validates the matching owned pending READY
file row and checks pending-purge ancestry. That transaction commits before job
renewal and Garage I/O. Job-row locks never overlap hierarchy/file locks. The
adapter rejects binding drift instead of constructing clients from persisted URLs
or resolving a new target. Only the matching configured client is supported.

DELETE has a ten-second call and five-second attempt bound, with two-second
connection/acquisition and five-second socket limits. Missing object success is
distinct from missing/inaccessible buckets. Sanitized transient/access/binding/
storage failures use the existing bounded retry/exhaustion policy. Cancellation
checks and live-token renewal reduce stale effects; a stale in-flight DELETE can
still finish, while fenced worker completion cannot overwrite a newer claim.

Success means the intended bytes are gone or already absent. Pending file metadata
and durable allocation/target remain; metadata finalization and folder purge are
deferred. Workers and consumers remain disabled by default; enabling workers now
permits actual eligible file deletion. Presigned/multipart transfers need a separate
writer-fencing design before integration. See [the storage contract, verification
and recovery runbook](../file-purge.md).
