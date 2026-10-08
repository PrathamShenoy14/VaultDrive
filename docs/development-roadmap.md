# Development roadmap

This roadmap separates repository evidence from future intent. Ordering can change after measurement or design review; unchecked items are not implemented.

## Current baseline

- [x] Spring Boot API with PostgreSQL and Flyway.
- [x] Registration, login, HS256 JWT issuance/validation, and protected endpoints.
- [x] Owner-scoped folder tree with create/list/get/rename/move/soft-delete/trash/restore.
- [x] S3-compatible upload/download through Garage, metadata listing, rename, move, trash, and restore.
- [x] Pending-purge schema/domain state and purge-aware trash/restore queries.
- [ ] Permanent-delete request flow: implemented only in the local unstaged working tree at the 2026-10-09 audit; validate and commit separately.

## Phase 1 — Stabilize permanent-delete intent

Goal: atomically choose exactly one of restore or permanent-delete for an eligible trashed file.

- Review the unstaged endpoint and lock-before-read refactor.
- Add or confirm a PostgreSQL-backed concurrency test for restore versus permanent-delete, not only mocked unit tests.
- Decide and document idempotency behavior for repeated permanent-delete requests.
- Run targeted file tests and the full suite, then commit this feature separately from documentation.

Exit criteria: the API contract, state transition, ownership boundary, race behavior, and tests agree; no Garage delete occurs in the request path.

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
- Add deterministic concurrency tests for create, rename, move, restore, trash, and purge-request conflicts.
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
