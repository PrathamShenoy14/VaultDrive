# VaultDrive architecture

Last reconciled with the repository on 2026-10-10.

## Status legend

- **Committed**: present at `954cb8e` (`feat: add file purge lifecycle state`) or earlier.
- **Working tree**: present in local unstaged code at the time of this audit; not part of the committed baseline or this documentation commit.
- **Planned**: design direction only; no complete implementation is present.

## System context

VaultDrive currently consists of one Spring Boot backend, PostgreSQL for relational state, and Garage through an S3-compatible API for file bytes. Clients authenticate with bearer JWTs. Web and mobile clients are planned but are not in this repository.

```text
Client
  | HTTPS + JWT
  v
Spring Boot API
  |-- PostgreSQL: users, folder tree, file metadata, lifecycle state
  `-- ObjectStorageService --> Garage/S3: opaque file bytes
```

PostgreSQL and Garage do not share a transaction. Code must make cross-system failure states explicit and recoverable.

## Implemented components

### Platform and persistence — Committed

- Java 21, Spring Boot 4.1.1, Maven wrapper, Spring MVC, Spring Data JPA, validation, and Spring Security.
- PostgreSQL schema managed by Flyway migrations V1-V12. Hibernate validates the schema (`ddl-auto=validate`) rather than creating it.
- Configuration comes from properties and environment variables. Secrets are not intended for source control.

### Authentication and security — Committed

- Registration normalizes email addresses, enforces BCrypt's 72-byte input limit, hashes passwords, and relies on a case-insensitive PostgreSQL unique index.
- Login verifies BCrypt hashes and issues HS256 JWT access tokens. The configured default lifetime is 15 minutes.
- The API is stateless. Registration, login, and health are public; all other endpoints require a valid bearer token.
- JWT subjects are user UUIDs. Issuer, signature, and expiration are validated.

Not implemented: refresh tokens, logout/revocation, account-state checks, rate limiting, key rotation, or asymmetric signing.

### Folder namespace — Committed

- Folders form an owner-scoped adjacency list through `parent_folder_id`.
- A composite foreign key ensures a child and parent have the same owner; a check prevents direct self-parenting.
- Active sibling names are unique for `(owner_id, parent_folder_id, name)`. PostgreSQL `NULLS NOT DISTINCT` makes the root a real namespace.
- Names are trimmed and validated but remain case-sensitive.
- Folder deletion is soft deletion. A descendant under a deleted ancestor becomes inaccessible through ancestor-chain validation even if the descendant row is not itself marked deleted.
- Restore uses the original parent when accessible, otherwise root, and generates ` (restored)` suffixes on collision.
- Folder permanent-delete requests record `purge_requested_at` only for owned, explicitly trashed folders. Pending purge makes the whole subtree immutable, including separately trashed descendants; restore cannot use root fallback to escape that ancestry.
- Application traversal prevents cycles during moves. The database does not independently prevent multi-row cycles.

### File storage and lifecycle — Committed

- PostgreSQL stores ownership, folder, display name, storage key, content type, byte count, upload status, and deletion timestamps.
- Garage stores bytes under an opaque key of the form `users/{ownerId}/files/{fileId}`. User-facing names are metadata, so rename and move do not copy objects.
- Upload reserves an `UPLOADING` metadata row in a short shared hierarchy transaction, streams bytes to S3 without a database transaction or hierarchy lock, then finalizes in a second short shared hierarchy transaction.
- Finalization reloads the owned `UPLOADING` row, checks its expected version, and revalidates the current destination ancestry. An inaccessible destination is conditionally changed to `FAILED`; the uploaded object is then deleted best-effort outside the transaction. A failed delete leaves the `FAILED` row as the durable object record. If the database finalization outcome itself is uncertain, the object is not blindly deleted and reconciliation of stale `UPLOADING` rows remains planned.
- Only active `READY` files can be listed, downloaded, renamed, moved, or trashed. Listings are paginated with a maximum page size of 100.
- Rename preserves the existing extension when the request omits it and rejects extension changes.
- Trash is metadata-only: `deleted_at` is set, the object remains in Garage, and the active name is released.
- Restore retains the same row, storage key, and object. It returns to the original accessible folder or root and resolves name collisions while preserving the extension.

### Pending purge request — Implemented

The request flow contains `purge_requested_at`, entity transitions, purge-aware trash/restore queries, `DELETE /api/v1/files/{fileId}/permanent`, and `DELETE /api/v1/trash/folders/{folderId}/permanent`. These states are represented independently from upload status:

| Lifecycle | `deleted_at` | `purge_requested_at` | User-visible behavior |
|---|---:|---:|---|
| Active | null | null | normal operations allowed when `READY` |
| Trash | set | null | listed in trash and restorable |
| Pending purge | set | set | hidden from active views and trash; not restorable |

The file endpoint accepts only an owned, trashed `READY` file whose purge has not already been requested. The folder endpoint accepts only an owned, explicitly trashed folder with no pending-purge ancestor or descendant. It takes the exclusive hierarchy lock before the fresh eligible-row lock and subtree checks. Overlapping parent/child requests are serialized: the first committed request wins and the second returns `404 Not Found`, so conflicting folder deletion intents are not recorded. Both endpoints return `202 Accepted`; ineligible, inaccessible, and repeated requests return `404 Not Found`. Neither request path calls Garage.

A pending-purge folder makes every descendant ineligible for restore, move, upload reservation/finalization, file purge request, or other metadata mutation. Folder and file restore explicitly check pending-purge ancestry before ordinary inaccessible-parent root fallback.

Accepted file and folder requests now also append a version-1 purge-intent event to `outbox_events` in the same JPA transaction through a mandatory-transaction writer. Business state and event commit or roll back together, including after flush. Ownership/eligibility rejection and repeated requests append no event. A folder request emits one root intent, not a subtree event list. This completes durable intent plus notification persistence only. No purge worker exists. Garage deletion, database-row finalization, retry/backoff, job claiming, crash recovery, observability, and dead-letter handling remain planned.

### Asynchronous processing — Partially implemented

ADR-0008 selects a PostgreSQL transactional outbox, RabbitMQ work notifications, PostgreSQL-backed durable job state, idempotent workers, and scheduler-driven recovery. Purge-request API transactions atomically persist business changes and outbox events; a future asynchronous publisher will deliver notifications; workers will claim durable jobs in PostgreSQL; and scheduled database scans will recover missed publication, missed notifications, retries, and interrupted work. Database polling is a recovery and publishing mechanism within this design, not an alternative to RabbitMQ.

RabbitMQ development infrastructure is deployed and verified on the Ubuntu VM as recorded in [Phase 1A setup instructions](rabbitmq-development.md). AMQP and management bind to VM loopback and Windows accesses them through SSH forwarding. Broker integration is not implemented. Phase 1B adds outbox schema/JPA persistence and purge-request insertion; no publisher, durable-job, worker, scheduler, retry, or recovery component is implemented. Their messaging topology, job schema, claim protocol, timing, and limits remain undecided. [ADR-0008](decisions/0008-transactional-outbox-rabbitmq.md) records payload versioning, delivery metadata, indexes, and the planned at-least-once/idempotent contract. Existing purge intents are not backfilled.

## Current consistency and concurrency model

PostgreSQL partial unique indexes are the final defense against duplicate active names. Services also perform pre-checks for clearer errors.

Folder create, rename, move, trash, restore, and permanent-delete request acquire an exclusive owner-scoped PostgreSQL transaction advisory lock before protected reads. File rename, move, trash, restore, permanent-delete request, upload reservation, and upload finalization acquire the matching shared lock before loading current file or ancestor state, then validate and write inside the same short transaction. Restore additionally takes a destination-scoped exclusive advisory lock before collision naming. The coordinator requires an active transaction and uses the transaction's JPA connection, so PostgreSQL releases locks on commit or rollback. Garage upload and cleanup calls occur outside these transactions and locks.

`files.version` supplies optimistic concurrency control between compatible shared-lock file mutations. Rename/move/trash use JPA optimistic updates; upload `UPLOADING -> READY|FAILED` changes use an explicit expected-status and expected-version conditional update. A stale same-file write fails with HTTP `409 Conflict` rather than overwriting another committed mutation. Restore and permanent-delete request use the same eligible-row pessimistic lock, so exactly one can consume a file's restorable Trash state. Restore name allocation is serialized per destination while different destination namespaces remain independent.

The advisory key derivation and staged migration constraints are recorded in ADR-0007. Folder operations for one owner remain serialized, key collisions can conservatively serialize different owners, database uniqueness constraints remain authoritative, and no throughput claim is made.

## Test evidence

The repository contains unit tests, MockMvc controller tests, Spring Security integration tests, PostgreSQL repository tests, folder concurrency integration tests, and a live S3-compatible storage integration test. PostgreSQL coordinator tests cover hierarchy lock compatibility/blocking, destination namespace lock compatibility/blocking, transaction release, and rejection outside a transaction. PostgreSQL races cover file move versus folder trash, file rename versus folder trash, optimistic same-file mutation conflicts, upload reservation versus folder trash, folder trash in the transfer gap before READY, competing finalization transitions, both file restore/permanent-delete winner orders, restore versus folder trash/move, same-name restores, folder purge request versus child/file restore, folder move, upload reservation, and both parent/child purge winner orders. Upload unit tests also cover known destination rejection cleanup, cleanup failure, and uncertain finalization behavior. Permanent-delete requests retain controller tests for accepted, not-found, and unauthenticated outcomes plus service, repository, constraint, and metadata transition coverage. The presence of a test is evidence of intended coverage, not proof that it passed on every machine; current verification results belong in the task/commit report.

Phase 1B PostgreSQL tests cover file/folder outbox commit, rollback after both flushes, real insertion-constraint failure, independent-transaction visibility, unauthorized/active/repeated request rejection, JSONB payload round-tripping, and invalid delivery/payload metadata. Existing file restore-versus-purge and parent/child folder-purge races also assert that only accepted intent emits an event. No publisher or consumer delivery behavior is tested or implemented.

## Known gaps and pending decisions

- Remaining ADR-0008 async implementation: publisher/claim protocol, durable-job schema, RabbitMQ topology, retry/recovery policy, historical intent backfill, retention, and worker ownership semantics.
- Remaining file-side coordination: future purge/reconciliation workers.
- Reconciliation for stale `UPLOADING`, `FAILED`, orphaned objects, and uncertain finalization.
- Folder/file purge workers and final subtree deletion semantics.
- Sharing/permissions, versioning, presigned or resumable transfers, quotas, malware scanning, audit logs, observability, and client applications.
- Production deployment, backup/restore, retention, encryption/key-management, and service-level objectives.

No benchmark, scale limit, or production-readiness claim has been established.

## Decision index

- [ADR-0001: Spring Boot, PostgreSQL, and Flyway](decisions/0001-spring-boot-postgresql-flyway.md)
- [ADR-0002: Separate metadata from Garage object storage](decisions/0002-garage-object-storage-separation.md)
- [ADR-0003: Stateless JWT authentication](decisions/0003-jwt-authentication.md)
- [ADR-0004: Folder namespace and uniqueness](decisions/0004-folder-namespace-and-uniqueness.md)
- [ADR-0005: File upload and trash lifecycle](decisions/0005-file-lifecycle.md)
- [ADR-0006: Durable pending-purge deletion](decisions/0006-pending-purge-async-deletion.md)
- [ADR-0007: Current namespace lock and granular successor](decisions/0007-concurrency-locking.md)
- [ADR-0008: Transactional outbox and durable asynchronous jobs](decisions/0008-transactional-outbox-rabbitmq.md)
