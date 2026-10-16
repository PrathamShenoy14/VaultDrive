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

Outbox events retain immutable UUID identities and versioned JSONB intent payloads. Aggregate references deliberately have no foreign key, so physical purge cannot erase its notification history. This creates an explicit retention/backfill responsibility rather than a cascade-cleanup shortcut. The Phase 1B due-event index prepared scans but did not itself implement claiming; Phase 1C adds that protocol below. Recording publication still leaves a broker-confirm/database-recording crash window, so publication and consumption must be idempotent and at least once. Phase 2A adds durable job state separately; workers remain unimplemented.

## Delivery and execution need separate durable state

Phase 2A uses the stable event UUID as the job primary key, so concurrent or terminal duplicate notifications cannot reset work. `ON CONFLICT DO NOTHING` avoids a check-then-insert race, while identity validation rejects conflicting interpretations of the same event. Creation can join a caller transaction and roll back; future broker acknowledgement must follow commit. No payload or raw failure text is required for this foundation.

`SKIP LOCKED` protects the short selection transaction; a persisted token and lease protect outcomes after that transaction ends. Completion/retry acquire the row lock before checking database-clock expiry, because a lock wait can consume the lease. Abandoned attempts consume the same bounded budget as known failures, and pre-recorded lease-plus-backoff deadlines prevent crash retries from ignoring delay. Terminal failures retain identity and references. A scan API is not an installed recovery scheduler, and existing outbox rows are not automatically jobs.

Fencing database outcomes cannot fence external effects: an expired worker may still complete storage I/O. A crash between an external effect and its database outcome can repeat that effect. Future execution needs idempotence, business-state validation and upload writer fencing. Keeping transactions short requires future orchestration to avoid an outer transaction too; suspending it with `REQUIRES_NEW` is insufficient. Job retention must preserve deduplication identity, or old notification replay can recreate completed work.

## A row lock is not a durable claim, and a broker ack can still mean no route

Phase 1C commits a UUID token and database-clock lease before RabbitMQ I/O, then conditionally records completion in another short transaction. `SKIP LOCKED` distributes concurrent claims while token/expiry checks reject stale completion. The lease cannot fence an already-running socket write, so expiry can overlap sends. Stable event IDs and future idempotent consumers are still necessary. A broker-confirm/PostgreSQL-recording crash replays the same notification identity.

Persistent messages and a durable quorum queue prepare broker durability, but mandatory returns must also be checked: an unroutable message may be returned and positively confirmed. A positive send call or positive ack alone is insufficient. Automatic attempts are bounded; expired claims include a persisted retry delay, and exhausted events remain `FAILED` for an explicit recovery decision. No queue consumer or physical purge is implemented. Integration tests use an approved isolated vhost and a separate test database schema, preserving unrelated events/topology.

## Confirmation deadlines do not bound socket writes

A timeout waiting for a publisher confirm starts too late to protect a polling thread blocked inside `send()`. Moving that send into an executor and timing out its future would leave the actual I/O and thread stuck. The focused hardening instead uses the existing client's non-blocking NIO socket path with finite frame capacity and enqueue waits. A full queue fails the operation; enqueue is not delivery, so uncertain frames still require stable IDs and idempotent replay. PostgreSQL leases remain independent of transport timeouts.

Shutdown must interrupt the actual queue/confirm waits and close socket channels, not drain a backed-up outbound queue indefinitely. Spring's early context-close hook normally initiates graceful scheduler shutdown before bean destruction; interrupt there explicitly, rather than assuming the later destroy-time setting interrupts immediately. Separate bounded I/O/shutdown resources and finite termination waits keep resource growth controlled. Deterministic tests saturate the real client queue while its I/O loop is held behind a latch; this proves the timeout/recovery path without pretending to be a live broker resource-alarm experiment. The scheduler test closes an application context to exercise that lifecycle hook. NIO's deprecation and composed (rather than total-operation) deadlines remain explicit trade-offs.

## Notification acknowledgement follows durable disposition

Phase 2B separates job-creation commit from RabbitMQ ACK. The transactional proxy must return after commit; an insert/flush is not enough. A commit failure rolls back the job and leaves the delivery unacknowledged. A lost ACK after commit redelivers the same event UUID without resetting job progress. Validation matches the committed immutable outbox envelope without requiring `PUBLISHED`, because broker delivery can precede publisher outcome recording.

An immediate negative-ACK/requeue loop can amplify a database outage. The consumer instead retries three short transactions with delays outside them, then stops and leaves broker delivery recoverable on explicit restart. Live tests establish the stop/redelivery behavior of the actual Spring container. This is a per-delivery budget, not a durable cross-restart counter; a rapid external restart loop would undermine it.

Changing immutable queue arguments to attach a DLX would disrupt existing topology. V15 instead commits a sanitized PostgreSQL quarantine before acknowledging invalid notifications. Fingerprints and controlled reason codes support inspection without storing sensitive raw bodies or exception text. The trade-off is inability to reconstruct arbitrary invalid bytes; retained outbox intent supports reviewed replay of known events. The consumer performs no file/folder mutation or storage deletion, so future execution still needs lifecycle and hierarchy validation. Retention and missed-notification/job backfill remain explicit gaps.

## Capacity, renewal and cancellation are separate guarantees

Phase 2C reserves a bounded local slot before the database claim, so waiting in memory cannot consume an arbitrary backlog of leases. Renewal database work uses a separate bounded pool: a stalled database call must not stall the control loop that notices elapsed local deadlines. Cancellation retains the slot until the real task returns, because cancelling a future or interrupting a thread does not prove external work stopped. Late renewal cancellation also needs synchronized thread attachment/detachment to avoid interrupting a different task after pool-thread reuse.

A renewable lease must move the durable crash retry deadline too; extending only expiration can otherwise lose abandoned-attempt backoff. PostgreSQL renewal uses row-lock-before-read plus live-token fencing and never increments attempts. Local monotonic lease proof starts before the database call, including its latency, rather than starting a new execution window on a delayed response. A separate runtime cap prevents endless renewal. These checks reduce local uncertainty but cannot fence external effects or account for every database clock jump.

Graceful shutdown first stops admission, lets finished results commit within a finite waiting budget, then stops renewal and requests interruption. Unfinished claims expire using the final durable deadlines, including any in-flight renewal that committed late. Waiting is bounded even when a daemon task ignores interruption; absolute task termination is not promised. Phase 2C supplied no production handler; Phase 2D.1 separately supplies real file byte purge. Tests signalling database results must wait for `afterCommit`, not merely a spied method's return inside its transactional proxy.

## Immutable targets and trusted storage writers

An event UUID identifies intent, not bytes. Phase 2D.1 copies the upload's exact
namespace/endpoint/region/bucket/key into an immutable PostgreSQL target in the
same transaction as purge intent and the v1 outbox event. Legacy rows cannot prove
their original location and receive no inferred target. Configuration drift fails
closed instead of redirecting an old job to today's bucket.

Garage 2.3.0 exposes internal version IDs without S3 version-specific DELETE:
persisting that response header would not create a deletion fence. We explicitly
accept trusted writers rather than claim storage-side immutability. A retained key
allocation prevents intentional application reuse even after file removal. One
committed write invocation and disabled SDK retries avoid creating competing upload
attempts; uncertain failed uploads require new keys and later reconciliation.

A stale DELETE can still finish outside its database lease. Permanent nonreuse
makes it address the same old object; job token/expiry fencing rejects its late
completion. An out-of-band overwrite or namespace/alias reassignment violates the
accepted contract and can destroy replacement bytes. Idempotence and lease fencing
are distinct from storage identity enforcement. See [file purge](file-purge.md).

## Restore eligibility includes the target's own purge state

Checking pending-purge ancestors alone misses a directly purged folder, including
a root folder with no ancestry. V11 correctly rejects clearing `deleted_at` while
`purge_requested_at` remains set, but that constraint failure can surface as HTTP
500. Folder restore now filters the freshly locked owned Trash row for a null
purge timestamp and uses the existing not-found exception before fallback or
mutation. The exclusive owner hierarchy lock followed by the pessimistic row
lock still serializes restore and purge; tests cover both commit winner orders,
direct service rejection with unchanged state, and a service-bypassing invalid restore
that the database rejects. The application guard supplies API semantics while
the unchanged constraint supplies the final invariant. Folder execution and
metadata finalization remain planned.

The direct regression is PostgreSQL service coverage plus the existing controller
not-found mapping coverage. An exploratory real-service MockMvc request with the
default Open EntityManager in View setting failed earlier in hierarchy coordination
(`Hierarchy coordination requires an active transaction`), before eligibility was
checked. That slice did not establish an end-to-end HTTP result. Phase 2E.2 later
confirmed the wrapper reporting mismatch and disabled OSIV explicitly; see
[the HTTP transaction diagnosis](decisions/0007-concurrency-locking.md#phase-2e2-http-transaction-joining).

## Byte success and metadata finalization need separate receipts

A byte DELETE can succeed before PostgreSQL knows it, and metadata removal can
commit before a worker records job completion. V17 shared per-file work retains
immutable target/lifecycle identity and establishes each receipt once. Separate
short transactions make a lost byte receipt repeat the same retained DELETE,
while a committed receipt lets later finalization skip Garage. Metadata removal
and its receipt are atomic; a missing file without durable proof is ambiguous.
SUCCEEDED still means bytes deleted/absent, preserving historical job meaning.

Resource checkpoints need hierarchy → fresh resource → work → job ordering.
Reading scalar resource state after coordination avoids JPA's cached stale entity
problem. A token/clock check after job-lock waits and at the final write must roll
back all earlier progress if ownership was lost. Job-only renewal does not take
resource locks, and no transaction spans Garage I/O.

A pending parent purge must retain file metadata even after standalone byte
deletion, or future paged discovery could miss an accepted descendant. Shared
hierarchy finalization and exclusive folder requests serialize both winner
orders. Until manifests exist, every pending ancestor conservatively blocks
removal. Historical SUCCEEDED recovery imports authoritative byte outcomes only
with matching retained V16 identity; bounded keyset pages advance past blocked
rows without resetting jobs. Recovery remains an explicit service operation,
not automatic scheduling or inferred legacy repair.

## Successful batches must not consume a failure budget

A long job can perform more than ten useful batches without failing. V18 separates
the monotonic claim count from bounded failed/abandoned executions. Count a real
failure at its fenced outcome or abandonment at locked recovery, never erase prior
failures after healthy work. Backoff follows failure ordinal, while each claim
pre-records the next abandonment deadline. Legacy terminal outcomes keep their
meaning; migration subtracts only one unresolved/successful execution from prior
claims when reconstructing failures.

A success flag cannot establish progress. CONTINUE requires a job-local revision
newer than the baseline captured when this claim started. Advance that revision
last, in the same transaction as a newly useful cursor/receipt/phase mutation,
using expected-version CAS and token/clock fencing. Rollback removes both work
and evidence; a committed checkpoint survives a lost continuation transition.
Recovery then charges abandonment and takes a new baseline, so old evidence cannot
authorize endless empty yields. Business meaning remains a handler contract;
generic infrastructure cannot infer whether an arbitrary cursor is useful.

One-second eligibility delay plus normal polling prevents completion callbacks
from becoming a busy dispatch loop. The actual handler keeps its slot until it
returns, even after cancellation; late CONTINUE cannot bypass runtime or shutdown
bounds. A legitimate grace-period yield can leave delayed PENDING for a later
worker. Healthy jobs may have unlimited useful invocations, with bounded runtime
per invocation. Load/fairness and actual process-kill behavior remain unmeasured.
Deterministic PostgreSQL checkpoint/accounting tests, worker cancellation/shutdown
tests and Garage regressions passed. The full safely runnable suite passed with
seven environment-gated RabbitMQ skips; see [the Slice 3 verification record](folder-purge-design.md#slice-3-continuation-foundation).

## Durable discovery is a checkpointed membership proof

Freezing structure at root intent makes indexed immediate-child/file keyset pages
stable without holding one transaction for the whole tree. A retained frontier
stores both cursors and completion flags. UUID ordering avoids OFFSET drift, and
a full page needs a later exhaustion scan. Persisting membership and its cursor
with the V18 checkpoint means a stale claim or duplicate revision rolls back all
three; a crash after commit resumes instead of losing or duplicating members.

A manifest captures observed lifecycle, not writer quiescence. All upload statuses
and unknown legacy bindings belong in membership even when they cannot be purged.
Separately trashed folders also belong to the structural subtree. Seal only after
every frontier is exhausted and parent/owner integrity is verified; repeated edges
signal corruption/cycles rather than productive traversal.

Standalone byte deletion can overlap discovery, but metadata finalization must
retain an accepted file until sealed membership proves it was enumerated. Exclusive
hierarchy discovery and shared finalization preserve both winner orders. The explicit adapter originally stayed unregistered; Slice 5 now invokes it
through the folder handler, which stops before bottom-up removal. Database deadlines bound scans/locks;
very large seal checks may still time out safely and need measured tuning.

## Shared byte proof separates execution ownership from job outcomes

A standalone file job can fail while a later folder claim safely deletes the same
verified allocation. Unique immutable per-file work lets either job contribute
one monotonic byte receipt without resetting the other job or replacing its target.
Attaching that receipt to sealed membership is new durable progress even when no
new DELETE occurs. Repeated attachments are not progress and cannot justify empty
CONTINUE. First blocked dispositions let traversal reach other eligible files;
unchanged blockers eventually spend bounded failures rather than yielding forever.

Never-admitted writes need a different proof from successful deletion. Under the
same file/allocation locks as write admission, permanent write preclusion and the
never-written receipt commit with member progress and the claim checkpoint. A
rollback restores all of them. An admitted writer remains unsafe even when FAILED
or old, because a timed-out PUT can finish later. Folder byte completion is thus a
proof about all recorded members, while folder/root removal remains a separate
phase and must not be claimed by the byte handler.


## Folder metadata finalization: proof survives the resource

Bottom-up removal needs retained child receipts as well as a sealed manifest:
an absent child row alone cannot distinguish committed purge from corruption.
V22 sealed counts detect missing records after removal; exact file work/allocation
and lifecycle checks keep byte proof separate from metadata proof. Each bounded
page commits removal and checkpoint together under hierarchy/resource/work/job
lock order, so stale claims roll back physical metadata changes.

Root deletion and FINALIZED proof share one transaction. The later job completion
can be lost without losing the outcome: replay validates retained proof and finishes
normally. Successful incomplete pages CONTINUE without consuming failure budget.
Standalone jobs share monotonic receipts but retain their own terminal accounting.
This provides deterministic transaction/crash-boundary recovery, not storage-side
writer fencing, operator-proof evidence, measured load limits or process-kill proof.


## An active transaction and a wrapper joined check can disagree

Phase 2E.2 reproduced the real HTTP failure before changing production code.
The service was reached through its CGLIB transaction proxy and Spring reported
an actual active transaction. With OSIV true, the injected and factory-bound
EntityManager reported unjoined while their identical native Hibernate delegate
reported joined and transaction-active. With OSIV false, all checks were true.
Spring ORM 7.0.9's ExtendedEntityManagerCreator checks a native-EntityManager-keyed
synchronization for the wrapper's non-JTA joined answer; JpaTransactionManager
owns the already-bound resource through its factory key instead. The failed guard
was a wrapper reporting mismatch, not proof that @Transactional was bypassed.

The smallest API fix is explicit spring.jpa.open-in-view=false. DTO responses
need no persistence context during view rendering, and the transaction manager
then creates the native EntityManager for each service transaction. Keep both
coordinator guards: weakening the joined check or adding REQUIRES_NEW would hide
the observed integration problem or change business atomicity. The explicit-true
diagnostic remains a failing-behavior control on this dependency set, so enabling
OSIV again requires revisiting the decision rather than assuming compatibility.

Mocked controller services and enclosing test transactions missed this request
lifecycle. Embedded HTTP requests with committed disposable fixtures exercise
security, controllers, transaction interceptors, EntityManager selection and
PostgreSQL locks together. Assert full persisted rows after rejected requests and
failure after outbox flush, then force both concurrency winner orders with latches
and inspect the waiting request's pg_locks entry. A successful response alone
does not establish rollback atomicity or lock-before-read behavior. See
[ADR-0007](decisions/0007-concurrency-locking.md#phase-2e2-http-transaction-joining).
