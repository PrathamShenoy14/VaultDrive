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
- PostgreSQL schema managed by Flyway migrations V1-V10. Hibernate validates the schema (`ddl-auto=validate`) rather than creating it.
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

### Pending purge request — Implemented

The request flow contains `purge_requested_at`, the entity transition, purge-aware trash/restore queries, and `DELETE /api/v1/files/{fileId}/permanent`. These states are represented independently from upload status:

| Lifecycle | `deleted_at` | `purge_requested_at` | User-visible behavior |
|---|---:|---:|---|
| Active | null | null | normal operations allowed when `READY` |
| Trash | set | null | listed in trash and restorable |
| Pending purge | set | set | hidden from active views and trash; not restorable |

The endpoint accepts only an owned, trashed `READY` file whose purge has not already been requested. It acquires the owner's namespace lock before fetching that eligible row, records `purge_requested_at` in PostgreSQL, and returns `202 Accepted`. Ineligible, inaccessible, and repeated requests return the same `404 Not Found`. The request path does not call Garage.

This completes only the durable request transition. No purge worker exists. Garage deletion, database-row finalization, retry/backoff, job claiming, crash recovery, observability, and dead-letter handling remain planned.

## Current consistency and concurrency model

PostgreSQL partial unique indexes are the final defense against duplicate active names. Services also perform pre-checks for clearer errors.

Folder create, rename, move, trash, and restore acquire an exclusive owner-scoped PostgreSQL transaction advisory lock before protected reads. File rename, move, and trash acquire the matching shared lock before loading current file or ancestor state, then validate and flush inside the same short transaction. The coordinator requires an active transaction and uses the transaction's JPA connection, so PostgreSQL releases locks on commit or rollback. These metadata-only operations perform no Garage I/O while holding the lock.

`files.version` supplies optimistic concurrency control between compatible shared-lock file mutations. A stale same-file write fails with HTTP `409 Conflict` rather than overwriting another committed mutation. File upload, restore, permanent-delete request, and lifecycle finalization retain their existing coordination and do not yet share the folder advisory-lock domain.

The advisory key derivation and staged migration constraints are recorded in ADR-0007. Folder operations for one owner remain serialized, key collisions can conservatively serialize different owners, database uniqueness constraints remain authoritative, and no throughput claim is made.

## Test evidence

The repository contains unit tests, MockMvc controller tests, Spring Security integration tests, PostgreSQL repository tests, folder concurrency integration tests, and a live S3-compatible storage integration test. PostgreSQL coordinator tests cover shared/shared compatibility, shared/exclusive and exclusive/exclusive blocking, transaction release, and rejection outside a transaction. PostgreSQL races cover file move versus folder trash, file rename versus folder trash, and optimistic same-file mutation conflicts. The permanent-delete request has controller tests for accepted, not-found, and unauthenticated outcomes; service tests for delegation, errors, and no object-storage access; and metadata-service tests for the durable transition and lock-before-read call order. A PostgreSQL-backed restore-versus-permanent-delete race test is not yet implemented. The presence of a test is evidence of intended coverage, not proof that it passed on every machine; current verification results belong in the task/commit report.

## Known gaps and pending decisions

- Async purge worker topology: database polling first versus a queue/outbox, plus retry and ownership semantics.
- Remaining file-side coordination: upload, restore, permanent-delete request, upload finalization, and future workers.
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
