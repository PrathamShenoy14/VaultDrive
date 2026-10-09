# ADR-0006: Durable pending-purge deletion

- Status: Request transition implemented; asynchronous worker planned
- Date: 2026-10-09

## Context

Permanent deletion must remove both PostgreSQL metadata and a Garage object. A local database transaction cannot roll back an S3 operation. Deleting Garage first can leave apparently restorable metadata with missing bytes; deleting metadata first can leak an object when S3 fails.

## Decision

Accept permanent deletion by recording durable intent in PostgreSQL before physical deletion. A file or explicitly trashed folder moves from Trash (`deleted_at` set, `purge_requested_at` null) to pending purge (both set). Pending-purge files are neither listed in Trash nor restorable. A pending-purge folder owns the intent for its entire subtree, which becomes immutable and cannot escape through restore root fallback. The request path must not call Garage and should return `202 Accepted`. A background worker will delete objects with retry semantics and finalize metadata only after known storage outcomes.

For the first API version, only already-trashed, `READY`, owned files with no existing purge request are eligible. The service acquires the shared owner hierarchy lock before fetching and pessimistically locking the eligible file row, then commits `purge_requested_at` in the same PostgreSQL transaction. Restore uses the same row lock, so only one transition can consume that Trash state. Ineligible, inaccessible, and repeated requests return `404 Not Found`; repeated requests are not treated as idempotent success. Direct active-to-purge behavior is deferred.

Folder requests take the exclusive owner hierarchy lock, pessimistically lock the freshly read owned and explicitly trashed row, and reject any pending-purge ancestor or descendant before recording intent. Concurrent overlapping parent/child requests therefore produce one committed intent; whichever request commits first wins and the other receives `404 Not Found`.

## Current implementation boundary

- Implemented: file and folder purge-request columns, entity transitions, purge-aware queries, file and folder permanent-delete endpoints, hierarchy-lock-before-read, eligible-row locking, subtree immutability, and non-overlapping folder intents.
- Tested at unit/MockMvc level: accepted, not-found, and unauthenticated HTTP outcomes; service delegation and absence of object-storage calls; metadata transition and lock-before-read interaction order.
- Tested: PostgreSQL-backed races for both restore/permanent-delete winner orders.
- Implemented in Phase 1B: file/folder purge-intent outbox events persisted atomically with new requests; schema and payload contract are recorded in ADR-0008.
- Implemented in Phase 1C: leased outbox publication, broker confirms, bounded publisher retries and expired publisher-claim recovery. This does not execute purge intent.
- Not implemented: file/folder workers, subtree traversal/deletion execution, worker claim/fencing/retry, final row deletion, job recovery, metrics, or purge repair tooling.

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
