# VaultDrive architecture

Last reconciled with the repository on 2026-10-09.

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
- PostgreSQL schema managed by Flyway migrations V1-V9. Hibernate validates the schema (`ddl-auto=validate`) rather than creating it.
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
- Application traversal prevents cycles during moves. The database does not independently prevent multi-row cycles.

### File storage and lifecycle — Committed

- PostgreSQL stores ownership, folder, display name, storage key, content type, byte count, upload status, and deletion timestamps.
- Garage stores bytes under an opaque key of the form `users/{ownerId}/files/{fileId}`. User-facing names are metadata, so rename and move do not copy objects.
- Upload writes an `UPLOADING` metadata row, streams bytes to S3, then marks the row `READY`. Storage failures are best-effort marked `FAILED`.
- If object upload succeeds but metadata finalization is uncertain, the object is not blindly deleted; reconciliation of stale `UPLOADING` rows is planned.
- Only active `READY` files can be listed, downloaded, renamed, moved, or trashed. Listings are paginated with a maximum page size of 100.
- Rename preserves the existing extension when the request omits it and rejects extension changes.
- Trash is metadata-only: `deleted_at` is set, the object remains in Garage, and the active name is released.
- Restore retains the same row, storage key, and object. It returns to the original accessible folder or root and resolves name collisions while preserving the extension.

### Pending purge — Partially committed

The committed baseline contains `purge_requested_at`, the entity transition, and purge-aware trash/restore queries. These states are represented independently from upload status:

| Lifecycle | `deleted_at` | `purge_requested_at` | User-visible behavior |
|---|---:|---:|---|
| Active | null | null | normal operations allowed when `READY` |
| Trash | set | null | listed in trash and restorable |
| Pending purge | set | set | hidden from active views and trash; not restorable |

The local unstaged working tree also contains `DELETE /api/v1/files/{fileId}/permanent`, returning `202 Accepted`, and moves restore/permanent-delete eligibility reads behind the per-user lock. These changes must be independently reviewed, tested, and committed.

No purge worker exists. Garage deletion, database-row finalization, retry/backoff, job claiming, crash recovery, observability, and dead-letter handling remain planned.

## Current consistency and concurrency model

PostgreSQL partial unique indexes are the final defense against duplicate active names. Services also perform pre-checks for clearer errors.

Folder and file namespace mutations acquire a pessimistic write lock on the owner's `users` row. Because both subsystems lock the same row, all namespace-changing operations for one user are effectively serialized. Different users can proceed concurrently.

This is deliberately simple and correctness-oriented, but it is coarse:

- unrelated folders for one user block each other;
- file and folder mutations for one user can block each other;
- some committed file flows read the target before entering the lock-owning transaction, which can leave a race window or stale decision;
- no throughput or contention benchmark currently exists.

The local restore/permanent-delete changes demonstrate the required lock-before-read pattern for mutually exclusive state transitions. A granular replacement is planned, but the lock key and mechanism are not yet decided. See ADR-0007.

## Test evidence

The repository contains unit tests, MockMvc controller tests, Spring Security integration tests, PostgreSQL repository tests, folder concurrency integration tests, and a live S3-compatible storage integration test. The presence of a test is evidence of intended coverage, not proof that it passed on every machine; current verification results belong in the task/commit report.

## Known gaps and pending decisions

- Async purge worker topology: database polling first versus a queue/outbox, plus retry and ownership semantics.
- Granular namespace locking and a safe migration path from the per-user lock.
- Reconciliation for stale `UPLOADING`, `FAILED`, orphaned objects, and uncertain finalization.
- Folder permanent deletion and subtree semantics.
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
