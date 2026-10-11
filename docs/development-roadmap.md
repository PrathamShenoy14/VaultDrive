# Development roadmap

This roadmap separates repository evidence from future intent. Ordering can change after measurement or design review; unchecked items are not implemented.

## Current baseline

- [x] Spring Boot API with PostgreSQL and Flyway.
- [x] Registration, login, HS256 JWT issuance/validation, and protected endpoints.
- [x] Owner-scoped folder tree with create/list/get/rename/move/soft-delete/trash/restore.
- [x] S3-compatible upload/download through Garage, metadata listing, rename, move, trash, and restore.
- [x] Pending-purge schema/domain state and purge-aware trash/restore queries.
- [x] Permanent-delete request flow: shared hierarchy lock, eligible-row lock, durable pending-purge transition, and `202 Accepted` response without Garage deletion.
- [x] Folder permanent-delete request: exclusive hierarchy lock, non-overlapping subtree intent, pending-purge ancestry barriers, and `202 Accepted` without deletion.

## Phase 1 — Durable permanent-delete intent (completed)

Goal: atomically choose exactly one of restore or permanent-delete for an eligible trashed file.

- [x] Add the authenticated permanent-delete request endpoint and return `202 Accepted` after recording intent.
- [x] Acquire the shared hierarchy lock before fetching and locking restore or permanent-delete eligibility.
- [x] Restrict the transition to owned, trashed `READY` files with no existing purge request.
- [x] Keep Garage deletion out of the HTTP request path.
- [x] Return `404 Not Found` for inaccessible, ineligible, and repeated requests rather than treating repetition as an idempotent success.
- [x] Cover the HTTP contract, service delegation, transition, and lock-before-read interaction order with unit and MockMvc tests.

PostgreSQL-backed races cover both restore/permanent-delete winner orders. Restore also coordinates with folder structural changes and serializes collision naming within its chosen destination.

Folder purge-request races cover child-folder restore, file restore root fallback, folder move, upload reservation, and both parent/child request winner orders. Physical subtree deletion remains a worker concern.

## Phase 1A — RabbitMQ development infrastructure

- [x] Add a pinned management image, persistent data volume, stable hostname, restart policy, health check, and required credentials from ignored environment configuration.
- [x] Inspect the existing VM Compose project and verify Windows-to-VM SSH forwarding before selecting loopback-only ports.
- [x] Deploy only RabbitMQ after explicit remote infrastructure approval and verify health, Windows AMQP reachability, and management access while preserving PostgreSQL/Garage (2026-10-09).

See [development setup and verification instructions](rabbitmq-development.md). This slice does not implement application integration or the ADR-0008 processing components.

## Phase 1B — PostgreSQL transactional outbox persistence

- [x] Add V12 outbox schema, JSONB payload versioning, delivery metadata, constraints, and indexes for future publisher scans.
- [x] Insert file/folder purge-intent events with Spring Data JPA inside the existing business transaction; reject standalone writer calls.
- [x] Add real PostgreSQL tests for atomic commit/rollback, uncommitted visibility, insertion failures, ownership/eligibility rejection, and repeated requests on both paths.

See [ADR-0008](decisions/0008-transactional-outbox-rabbitmq.md). Phase 1B covers persistence; Phase 1C adds publication and publisher recovery; Phase 2A adds job persistence; Phase 2B adds consumer ingestion; Phase 2C adds opt-in worker scheduling/renewal and supported-type recovery. Physical purge handlers and backfill of historical intents remain unimplemented. Test execution results belong in the task report.

## Phase 1C — Reliable outbox publication

- [x] Add V13 token/lease state and use PostgreSQL `FOR UPDATE SKIP LOCKED` for short concurrent claims.
- [x] Publish outside database transactions with stable event IDs, persistent messages, durable direct/quorum topology, correlated confirms, and mandatory-return checks.
- [x] Fence completion by unexpired token; bound retries/backoff and recover abandoned claims, retaining exhausted events as `FAILED`.
- [x] Add concurrent publisher, failure/expiry/replay, and simultaneous file/folder purge tests; isolate live broker tests from production topology.

See [publisher development/recovery instructions](outbox-publishing.md) and ADR-0008. Execution results belong in the task report. Polling is opt-in; worker execution, historical-intent backfill and job recovery remain separate phases.

## Phase 2 — Build a minimal reliable purge worker

Goal: remove Garage objects asynchronously and finalize metadata without pretending PostgreSQL and S3 share a transaction.

- Partially implemented ADR-0008 architecture: development RabbitMQ, atomic purge-request outbox persistence, leased publication, durable job lifecycle primitives, consumer ingestion and opt-in worker scheduling/renewal are present; physical purge handlers and missed-job backfill remain planned.
- Consume at-least-once notifications idempotently; extend atomic outbox persistence to future business transitions where required.
- Have idempotent workers claim durable jobs in PostgreSQL; use scheduled database scans to recover missed notifications, retries, and interrupted work. Database polling is a recovery and publishing mechanism, not an alternative execution topology.
- Define safe job claiming for multiple worker instances, retry/backoff, attempt metadata, and terminal-failure handling.
- Treat object deletion as idempotent or explicitly handle an already-missing object.
- Delete/finalize the database row only after the storage outcome is known.
- Add tests for success, transient storage failure, process interruption, duplicate delivery, and concurrent workers.
- Add operational signals for pending age, retries, failures, and orphan detection.

Exit criteria: a committed deletion request survives restart, is retried safely, cannot be restored, and eventually reaches a documented terminal state.

### Phase 2A — Durable PostgreSQL job foundation (completed)

- [x] Add V14 stable event/job identity, typed aggregate references, lifecycle/attempt/lease timestamps, constraints and scan indexes.
- [x] Add idempotent transactional creation and independent short claim/complete/retry transactions with row locking and durable token fencing.
- [x] Persist ten-attempt budgets, 30-second leases, one-to-60-second exponential backoff, abandoned-claim recovery and terminal `FAILED` state.
- [x] Cover competing claims, duplicate creation, stale completion, lease recovery, rollback, backoff and exhaustion against PostgreSQL.
- [x] Document the [state machine and crash behavior](background-jobs.md) in ADR-0008 and architecture notes.

Phase 2A supplies persistence primitives; Phase 2B connects notification delivery to
job creation and Phase 2C adds scheduling/renewal. Garage/folder purge handlers,
stale-upload reconciliation and notification backfill remain planned. The overall Phase 2
exit criteria remain pending. Execution results belong in task reports.

### Phase 2B — RabbitMQ consumer integration (completed)

- [x] Add a disabled-by-default manual-ACK consumer for existing v1 file/folder purge events, without broker topology changes.
- [x] Validate strict envelopes and committed outbox identity; create/find the stable event-ID job in one short transaction before ACK.
- [x] Preserve job progress on duplicate/redelivered notifications, including commit-before-ACK loss.
- [x] Bound database retries to three attempts with one-/two-second delays; stop with unacked work on exhaustion for explicit restart/redelivery.
- [x] Add V15 sanitized PostgreSQL quarantine for permanently invalid messages; commit quarantine before ACK, retaining no raw payload/exception.
- [x] Cover PostgreSQL rollback/commit ordering and live isolated-vhost delivery, quarantine, failure/stop and redelivery.
- [x] Document [consumer configuration, acknowledgement and recovery](purge-consumer.md).

No purge worker, Garage deletion, upload reconciliation or backfill is implemented.
Runtime startup remains disabled until explicitly enabled; verification does not
deploy the development API. Raw invalid-message recovery and outbox/job/quarantine
retention remain open; Phase 2C below adds supported-type scheduling and renewal.

### Phase 2C — Durable job recovery and worker foundation (completed)

- [x] Add opt-in fixed-delay claiming/dispatch for registered handler types, using existing PostgreSQL token/lease claims.
- [x] Reserve capacity before claiming; bound handler and renewal pools/queues and retain slots for interrupt-ignoring tasks.
- [x] Add live-token lease renewal that extends the persisted crash retry deadline without consuming attempts; preserve V14/V15 unchanged.
- [x] Enforce monotonic local lease/runtime limits and isolate renewal database calls from cancellation monitoring.
- [x] Stop admission on context closure, bound graceful waiting, interrupt unfinished work and leave leases recoverable.
- [x] Add a minimal explicit-result handler API, with no fake-success production handlers.
- [x] Cover concurrent schedulers, renewal/stale tokens, lock-wait expiry, crash/timeout recovery, capacity, slow renewal and shutdown against PostgreSQL.
- [x] Document [worker configuration and recovery](job-workers.md).

All scheduling stays disabled by default. No file/folder purge handler, Garage
deletion, stale-upload reconciliation or missed-notification/job backfill exists.
Actual storage execution, retention/reset tooling and operational tuning remain open.

## Phase 3 — Refine concurrency without weakening correctness

Goal: reduce unnecessary same-user serialization.

- Inventory every namespace-changing operation and its read/lock/write order.
- Extend deterministic PostgreSQL-backed concurrency coverage as new namespace and purge-worker paths are implemented.
- Establish a reproducible contention workload and baseline; do not infer performance from unit tests.
- Compare candidate lock scopes: parent-folder row, dedicated namespace lock row, PostgreSQL advisory lock, or optimistic write plus unique-constraint retry.
- Define stable lock ordering for operations spanning source and destination namespaces.
- Implement one bounded strategy and retain database uniqueness constraints as the final guard.

Exit criteria: correctness tests remain green and measured contention improves for unrelated namespaces. The exact target is pending baseline data.

## Phase 4 — Reconciliation and operability

- Reconcile stale `UPLOADING` rows and possible orphaned Garage objects.
- Add health/readiness checks that distinguish PostgreSQL and object-storage failures.
- Add structured logging, request correlation, metrics, and tracing.
- Define backup/restore and disaster-recovery procedures.
- Define storage quotas and lifecycle retention.

## Phase 5 — Sharing and authorization

- Define owner, collaborator, and link-sharing permission models before schema work.
- Add resource-level authorization and non-leaking errors.
- Add revocation, expiry, and audit events.
- Threat-model enumeration, confused-deputy, and link-token risks.

## Phase 6 — Transfer scalability

- Evaluate presigned transfers so application servers do not proxy all bytes.
- Define multipart/resumable upload state, checksum verification, expiry, and cleanup.
- Add versioning only after its retention and quota semantics are decided.
- Add malware/content processing as asynchronous work if required.

## Phase 7 — Clients and deployment

- Next.js web client and React Native client.
- Containerized deployment and environment-specific configuration.
- CI that provisions PostgreSQL and S3-compatible dependencies.
- Production security review, secret rotation, rate limiting, and capacity tests.

## Explicitly undecided

- Physical purge execution/ownership, missed-job backfill, operational tuning and event/job/quarantine retention within ADR-0008. Publisher policy is decided in Phase 1C; job identity/states/fencing/retries in Phase 2A; consumer disposition in Phase 2B; bounded scheduling/renewal/shutdown in Phase 2C.
- Granular lock mechanism and namespace key.
- Hard-delete retention delay and user-visible cancellation policy.
- Refresh-token/logout design and JWT key evolution.
- Sharing schema, quota model, deployment platform, and performance targets.
