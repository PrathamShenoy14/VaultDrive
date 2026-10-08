# Implementation learnings

This is a living explanation of lessons demonstrated by committed VaultDrive code. It is not a claim that every production concern is solved.

## Database migrations own the schema

Flyway provides an ordered, reviewable history while Hibernate validates mappings against it. This prevents accidental schema generation from hiding migration mistakes. Once shared/applied, a migration should remain immutable; corrections use a new migration.

## Constraints close race windows

An application-level `exists` check gives a friendly error but two requests can both pass it. PostgreSQL partial unique indexes remain the final authority. `NULLS NOT DISTINCT` is particularly useful because root items have `parent/folder_id = NULL` but still need one shared root namespace.

## Ownership belongs in relationships and queries

Folder parentage uses `(parent_folder_id, owner_id)` to prevent cross-owner trees. File/folder reads include the owner, and inaccessible resources generally look absent. This is stronger than loading by ID and checking ownership later.

## Soft deletion changes more than one query

Once `deleted_at` exists, every active read and uniqueness rule must define whether deleted rows count. Adding `purge_requested_at` further split "deleted" into restorable Trash and irreversible pending purge, so trash and restore queries had to exclude purge requests.

## Upload status and deletion lifecycle are separate concerns

`UPLOADING`, `READY`, and `FAILED` describe whether bytes were created successfully. `deleted_at` and `purge_requested_at` describe user lifecycle. Keeping these dimensions separate avoids an overloaded enum whose states become hard to reason about.

## PostgreSQL and S3 cannot be one local transaction

`@Transactional` can roll back PostgreSQL but cannot undo a successful S3 delete. Permanent deletion therefore records durable intent first and is designed for retryable background work. This favors temporary storage leakage over showing restorable metadata whose bytes are already gone.

## Stable storage keys make metadata operations cheap

Storage keys use immutable owner/file UUIDs rather than display names or folder paths. Rename, move, trash, and restore update PostgreSQL only; they do not copy large objects. User-facing hierarchy is a metadata concern.

## Lock migrations must preserve one coordination domain

Folder structural operations use an owner-scoped exclusive PostgreSQL transaction advisory lock. File rename, move, and trash use its shared counterpart before reading current hierarchy state, so these paths coordinate while unrelated file mutations may overlap. Optimistic file versions then prevent compatible shared-lock requests from losing same-row updates. Upload, restore, permanent-delete request, and lifecycle finalization remain outside this advisory-lock domain, so the staged change is still not complete concurrency safety.

## Lock-before-read matters for competing transitions

Reading a file or its ancestor chain before acquiring the hierarchy lock permits a folder mutation to invalidate that state. The implemented rename/move/trash pattern is: acquire the shared owner lock, read and validate current state inside the same transaction, then mutate. Restore and permanent-delete request correctly take their existing user-row lock before reading eligible Trash state, but still need migration to the common hierarchy coordination domain.

## Failure handling should preserve the most useful truth

If an S3 upload fails, best-effort marking as `FAILED` keeps diagnostic state. If upload succeeds but the metadata outcome is uncertain, blindly deleting the object may create a worse inconsistency. Reconciliation is intentionally planned, though not yet implemented.

## Tests should live at the layer that owns the guarantee

- Unit tests cover service decisions and failure paths.
- MockMvc tests cover HTTP contracts and authentication.
- Repository tests prove PostgreSQL queries and constraints.
- Concurrency integration tests prove lock behavior.
- S3 integration tests prove the adapter against a compatible service.

A mocked repository cannot prove a unique index or SQL predicate; a controller test cannot prove two transactions serialize.
