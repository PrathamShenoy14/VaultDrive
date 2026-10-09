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

## Phase 2 — Build a minimal reliable purge worker

Goal: remove Garage objects asynchronously and finalize metadata without pretending PostgreSQL and S3 share a transaction.

- Decide job source: begin with database polling unless measured requirements justify a broker; a queue/outbox remains an option, not a current dependency.
- Define safe job claiming for multiple worker instances, retry/backoff, attempt metadata, and terminal-failure handling.
- Treat object deletion as idempotent or explicitly handle an already-missing object.
- Delete/finalize the database row only after the storage outcome is known.
- Add tests for success, transient storage failure, process interruption, duplicate delivery, and concurrent workers.
- Add operational signals for pending age, retries, failures, and orphan detection.

Exit criteria: a committed deletion request survives restart, is retried safely, cannot be restored, and eventually reaches a documented terminal state.

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

- RabbitMQ or another broker versus database polling/outbox.
- Granular lock mechanism and namespace key.
- Hard-delete retention delay and user-visible cancellation policy.
- Refresh-token/logout design and JWT key evolution.
- Sharing schema, quota model, deployment platform, and performance targets.
