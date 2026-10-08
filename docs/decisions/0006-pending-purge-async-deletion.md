# ADR-0006: Durable pending-purge deletion

- Status: Request transition implemented; asynchronous worker planned
- Date: 2026-10-09

## Context

Permanent deletion must remove both PostgreSQL metadata and a Garage object. A local database transaction cannot roll back an S3 operation. Deleting Garage first can leave apparently restorable metadata with missing bytes; deleting metadata first can leak an object when S3 fails.

## Decision

Accept permanent deletion by recording durable intent in PostgreSQL before physical deletion. A file moves from Trash (`deleted_at` set, `purge_requested_at` null) to pending purge (both set). Pending-purge files are neither listed in Trash nor restorable. The request path must not call Garage and should return `202 Accepted`. A background worker will delete the object with retry semantics and finalize metadata only after a known storage outcome.

For the first API version, only already-trashed, `READY`, owned files with no existing purge request are eligible. The service acquires the owner's namespace lock before fetching eligibility and commits `purge_requested_at` in the same PostgreSQL transaction. Ineligible, inaccessible, and repeated requests return `404 Not Found`; repeated requests are not treated as idempotent success. Direct active-to-purge behavior is deferred.

## Current implementation boundary

- Implemented: V9 column, entity field/transition, purge-aware trash/restore repository queries, `DELETE /api/v1/files/{fileId}/permanent`, and lock-before-read handling for restore and permanent-delete request.
- Tested at unit/MockMvc level: accepted, not-found, and unauthenticated HTTP outcomes; service delegation and absence of object-storage calls; metadata transition and lock-before-read interaction order.
- Not yet tested: a PostgreSQL-backed race between restore and permanent-delete request.
- Not implemented: worker, claim protocol, retries/backoff, final row deletion, broker/outbox, metrics, or repair tooling.

## Consequences

- The user decision becomes durable even when Garage is unavailable.
- Temporary storage leakage is preferred to metadata that promises bytes no longer present.
- Delivery and object deletion must be idempotent because retries or crashes can repeat work.
- Pending-purge age and repeated failures need operational visibility.
- A database-polling worker can be the minimal first implementation; introducing a broker should follow a concrete need and an outbox/reliability design.

## Alternatives considered

- Synchronous Garage-first deletion: risks broken restorable metadata.
- Synchronous database-first deletion: can leak untracked objects without a durable retry record.
- Distributed transactions: not realistically provided across PostgreSQL and S3-compatible storage.
- Add `DELETING` to upload status: conflates readiness and deletion lifecycle.
