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

For folders, pending purge is inherited state: a descendant row can have a null `purge_requested_at` but still be immutable because an ancestor owns the deletion intent. Restore must distinguish ordinary deleted ancestry, where root fallback is allowed, from pending-purge ancestry, where fallback would incorrectly let an item escape the subtree.

## Upload status and deletion lifecycle are separate concerns

`UPLOADING`, `READY`, and `FAILED` describe whether bytes were created successfully. `deleted_at` and `purge_requested_at` describe user lifecycle. Keeping these dimensions separate avoids an overloaded enum whose states become hard to reason about.

## PostgreSQL and S3 cannot be one local transaction

`@Transactional` can roll back PostgreSQL but cannot undo a successful S3 delete. Permanent deletion therefore records durable intent first and is designed for retryable background work. This favors temporary storage leakage over showing restorable metadata whose bytes are already gone.

## Stable storage keys make metadata operations cheap

Storage keys use immutable owner/file UUIDs rather than display names or folder paths. Rename, move, trash, and restore update PostgreSQL only; they do not copy large objects. User-facing hierarchy is a metadata concern.

## Lock migrations must preserve one coordination domain

Folder structural operations and folder purge requests use an owner-scoped exclusive PostgreSQL transaction advisory lock. File rename, move, trash, restore, permanent-delete request, upload reservation, and upload finalization use its shared counterpart before reading current hierarchy state, so these paths coordinate while unrelated file mutations may overlap. Optimistic file versions prevent compatible shared-lock requests from losing ordinary same-row updates; upload lifecycle changes additionally require the expected `UPLOADING` state. Restore and permanent-delete lock the eligible Trash row so only one transition wins, while restore uses a destination-scoped advisory lock to serialize collision naming without serializing other destinations. Folder purge requests reject any pending-purge ancestor or descendant, making overlapping parent/child intent first-committer-wins. Future purge and reconciliation workers remain outside this domain, so the staged change is still not complete concurrency safety.

## Lock-before-read matters for competing transitions

Reading a file or its ancestor chain before acquiring the hierarchy lock permits a folder mutation to invalidate that state. The implemented file-mutation pattern is: acquire the shared owner lock, read and validate current state inside the same transaction, then mutate. When compatible shared-lock operations consume one eligibility state or allocate a name, a narrower row or destination lock is still required; the owner hierarchy lock alone does not prevent those races.

## Failure handling should preserve the most useful truth

If an S3 upload fails, best-effort marking as `FAILED` keeps diagnostic state. If upload succeeds but finalization deterministically rejects an inaccessible destination, committing `FAILED` first creates a durable object record before best-effort cleanup; a failed cleanup therefore leaves tracked, not orphaned, bytes. If the database outcome is uncertain, blindly deleting the object may create a READY row with missing bytes, so the object remains and reconciliation is still required.

## Tests should live at the layer that owns the guarantee

- Unit tests cover service decisions and failure paths.
- MockMvc tests cover HTTP contracts and authentication.
- Repository tests prove PostgreSQL queries and constraints.
- Concurrency integration tests prove lock behavior.
- S3 integration tests prove the adapter against a compatible service.

A mocked repository cannot prove a unique index or SQL predicate; a controller test cannot prove two transactions serialize.

## Broker infrastructure is a separate implementation boundary

A management-enabled RabbitMQ container and persistent volume prepare development infrastructure; they do not implement reliable background work. ADR-0008 still requires atomic outbox persistence and PostgreSQL-backed job recovery. Keep the broker's hostname stable with its data volume, and distinguish an application/listener health check from authenticated AMQP behavior and message processing.

With VirtualBox NAT, a VM loopback publication is reached from Windows through an SSH local forward, not directly through Windows localhost. A PostgreSQL protocol probe through that path verified connectivity before choosing RabbitMQ ports. After approved deployment, Windows received an AMQP connection-start frame and the management UI through the tunnel; authenticated management access was verified on the VM. The additive Compose file and service-scoped `up --no-deps --no-recreate` left PostgreSQL/Garage container identities, start times, mounts, networks, port mappings, and existing configuration fingerprints unchanged. The persistent volume is configured, but no restart or message-persistence experiment was performed in this slice.

## Flush is not commit; outbox insertion belongs to the business transaction

Phase 1B appends file/folder purge-intent events after the existing locked business transition is flushed, using the same JPA transaction. `Propagation.MANDATORY` prevents the writer from quietly starting an independent transaction. PostgreSQL constraint failure during event insertion or an outer rollback must remove both writes; mocks alone cannot prove this. Independent-transaction visibility checks also establish that a future publisher cannot see uncommitted events.

Outbox events retain immutable UUID identities and versioned JSONB intent payloads. Aggregate references deliberately have no foreign key, so physical purge cannot erase its notification history. This creates an explicit retention/backfill responsibility rather than a cascade-cleanup shortcut. The Phase 1B due-event index prepared scans but did not itself implement claiming; Phase 1C adds that protocol below. Recording publication still leaves a broker-confirm/database-recording crash window, so publication and consumption must be idempotent and at least once. Workers and durable job state remain unimplemented.

## A row lock is not a durable claim, and a broker ack can still mean no route

Phase 1C commits a UUID token and database-clock lease before RabbitMQ I/O, then conditionally records completion in another short transaction. `SKIP LOCKED` distributes concurrent claims while token/expiry checks reject stale completion. The lease cannot fence an already-running socket write, so expiry can overlap sends. Stable event IDs and future idempotent consumers are still necessary. A broker-confirm/PostgreSQL-recording crash replays the same notification identity.

Persistent messages and a durable quorum queue prepare broker durability, but mandatory returns must also be checked: an unroutable message may be returned and positively confirmed. A positive send call or positive ack alone is insufficient. Automatic attempts are bounded; expired claims include a persisted retry delay, and exhausted events remain `FAILED` for an explicit recovery decision. No queue consumer or physical purge is implemented. Integration tests use an approved isolated vhost and a separate test database schema, preserving unrelated events/topology.

## Confirmation deadlines do not bound socket writes

A timeout waiting for a publisher confirm starts too late to protect a polling thread blocked inside `send()`. Moving that send into an executor and timing out its future would leave the actual I/O and thread stuck. The focused hardening instead uses the existing client's non-blocking NIO socket path with finite frame capacity and enqueue waits. A full queue fails the operation; enqueue is not delivery, so uncertain frames still require stable IDs and idempotent replay. PostgreSQL leases remain independent of transport timeouts.

Shutdown must interrupt the actual queue/confirm waits and close socket channels, not drain a backed-up outbound queue indefinitely. Spring's early context-close hook normally initiates graceful scheduler shutdown before bean destruction; interrupt there explicitly, rather than assuming the later destroy-time setting interrupts immediately. Separate bounded I/O/shutdown resources and finite termination waits keep resource growth controlled. Deterministic tests saturate the real client queue while its I/O loop is held behind a latch; this proves the timeout/recovery path without pretending to be a live broker resource-alarm experiment. The scheduler test closes an application context to exercise that lifecycle hook. NIO's deprecation and composed (rather than total-operation) deadlines remain explicit trade-offs.
