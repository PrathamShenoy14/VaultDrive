# Implementation learnings

This is a living explanation of lessons demonstrated by committed VaultDrive code. It is not a claim that every production concern is solved.

## Sharing M1: permission is a current-state decision

Direct grants are entry points, not copies of a subtree's authorization. Resolving
the strongest grant along current owner-consistent ancestry makes moves and
root-fallback restores predictable without grant rewrites. Structural accessibility
and permission to inspect a parent differ: a directly shared file may be read while
its private parent ID is redacted.

A shared advisory lock coordinates folder/grant changes but allows file moves.
Separate location and grant queries could therefore authorize the wrong snapshot.
M1 resolves metadata, ancestry, lifecycle and permission in one bounded PostgreSQL
statement, independently of cached JPA entities. Grant mutations take the exclusive
owner hierarchy lock without touching file versions or retained storage evidence.

Revocation blocks the next download authorization, while an admitted backend stream
may finish. Holding hierarchy locks during Garage I/O would couple permission changes
to external latency. PostgreSQL race and storage-boundary tests establish that
separation. Filter permission/lifecycle before pagination to avoid omitting accessible
entry points or exposing private totals. See [M1](sharing-m1.md).

## Completion evidence must survive lost acknowledgements and blocked destinations

M3 freezes the exact validated Garage part manifest and chooses completion in one
commit, then grants a single-assignment dispatch identity in another. A crash
between those commits can resume; a crash after permission cannot send again.
The progress revision can advance without changing V27's permanent decision.

Garage 2.3.0 returns a composite SHA-256 over the ordered binary part hashes,
without an AWS-style part-count suffix. Verified size/type, backend-set identity
metadata and this checksum can recover a completed object only under the sole
control-plane/nonreuse contract. HEAD absence and NoSuchUpload remain unknown.
The composite is not a full-file hash, and an ETag is not substituted for either.

An independent immutable receipt records positive facts even if folder trash/purge
has won in the meantime. A separate fresh status/version CAS controls READY, so
recovery after a receipt needs no Garage call. None of that establishes accepted
writer termination or physical purge authority, including for multipart READY.
See [M3 conditions, crash boundaries and evidence](multipart-completion-recovery.md).

## Dispatch permission and part authorization must survive retries

M2A commits a permanent initiation dispatch record before Garage I/O. A crash
before sending and a lost successful response both leave INITIATING; neither
permits a replacement dispatcher. This sacrifices availability rather than
creating untracked multipart identities. A known ID must commit before any URL.

Part expectations commit before signing and survive signing failures. Refresh
reuses their registration UUID, exact geometry and canonical SHA-256. Database
immutability plus signed length/checksum excludes M0's arbitrary same-part
replacement race. A short encoded signature lifetime still depends on bounded
storage/signer clock skew, and expiry never proves accepted writers have ended.
See [M2A contracts and evidence](multipart-part-transfers.md).

## Protocol identity must precede new evidence

M1 permanently labels allocations before multipart storage work exists. A reserved
multipart session has not started writing, but that does not make it a synchronous
never-admitted reservation: its future capabilities need different cleanup proof.
Database protocol guards prevent legacy admission, receipts, expiry and purge from
silently adopting the new allocation. Deferred pairing constraints allow atomic
file/allocation/session creation while rejecting orphan allocations at commit.

Idempotency returns the original identity and deadline, never renewed authority.
Separate immutable initiation and mutually exclusive decision UUIDs preserve the
first outcome across retries; state revision advances only on actual transitions.
Neither an abandonment decision nor OPEN with a durable upload ID proves storage
completion or writer termination. M1 therefore rejects all multipart READY and
removal paths until later protocol-specific evidence exists. See the
[session contract and test boundary](multipart-upload-sessions.md).

## Multipart capability identity is separate from request termination

M0 observed accepted UploadPart requests returning 200 after complete, abort and
URL expiry. The tested late requests left completed bytes unchanged, but neither
acknowledgements nor subsequent absence establish physical part reclamation.
Completion response loss also left correct bytes while the caller had only an
SDK exception; duplicate completion returned NoSuchUpload. Outcome evidence and
authorization decisions must remain separate from client/network errors.

Concurrent different-content writes to one part exposed an intermittent selected
ETag versus downloaded-content mismatch. Immutable signed SHA-256 and length are
therefore required for the proposed protocol; ten same-content checksum-bound
races verified expected bytes. Unsigned length did not constrain the submitted
size. These are bounded single-deployment experiments, not a general S3 proof.
Missing CORS, unverified HTTPS/browser access and substantial clock skew separately
block browser enablement. See [M0 evidence and limitations](garage-multipart-capabilities.md).

## Inventory evidence has a shorter lifetime than recovery authority

Phase 2E.5B1 reads allocation admission and metadata/ancestry in one PostgreSQL
statement snapshot. A competing admission can commit immediately afterward, so
NEVER_ADMITTED proves an observation, not permission to expire. FAILED and age
provide no writer-terminal evidence. Historical bindings must remain historical;
current client configuration cannot fill unknown fields.

UUID keyset cursors advance over uncertain, inconsistent and failed observations;
state changes cannot shift offsets. Independent read-only transactions, statement
and lock-wait timeouts, bounded ancestry and a page admission budget limit one
explicit call. Repeated all-table snapshots and database-enforced rejection of
writes establish reporting isolation; zero storage-boundary/SDK interactions
establish no Garage operations in this slice. B2 now separately supplies atomic
preclusion and durable evidence. See [classification and verification](stale-upload-inventory.md).

## Expiry policy is safe only behind permanent admission preclusion

B2 uses age to decide when to reject a delayed reservation, never to infer absent
bytes. Shared hierarchy coordination stabilizes ancestry against folder mutation;
fresh file and allocation locks serialize expiry with admission. Admission first
leaves expiry blocked forever for that allocation. Preclusion first makes delayed
beginWrite fail before PUT. The expected-status/version CAS also preserves a
competing READY winner and rejects stale finalization after expiry.

FAILED and a preclusion timestamp alone cannot distinguish ordinary failure from
folder purge or intentional expiry. Two V24 fields on the retained immutable
allocation record expiry time and resulting FAILED version without inventing
storage-success or purge evidence. Preclusion, FAILED and evidence share one short
transaction; injected failures after preclusion and after metadata mutation must
restore all three. Replays preserve evidence and a new same-name reservation uses
a new key. Deterministic tests observe real PostgreSQL blockers before releasing
each winner, while zero SDK interactions establish the delayed-admission boundary.
No database lock or recovery transaction spans Garage I/O. Accessible ancestry is
required conservatively; pending purge owns its unchanged lifecycle snapshots.
See [the exact expiry contract and evidence](stale-upload-expiry.md).

## PUT success and READY need independent commits

B3 records a database-generated receipt only after synchronous PUT and stream
closure both succeed. The immutable allocation binding and one consumed admission,
including expected metadata version/destination, give the receipt its identity;
ETag, HEAD, age and process death cannot supply that provenance. Historical
admissions cannot be retroactively assigned a receipt snapshot.

A separate receipt commit makes a crash before READY recoverable while preserving
the unavoidable PUT-to-receipt gap. Lost receipt acknowledgement requires a fresh
PostgreSQL read. Lost READY acknowledgement resolves from matching authoritative
READY. Recovery never repeats storage I/O or compensates with deletion. A trashed
or pending-purge destination blocks unchanged; later restore may make an
UPLOADING receipt eligible, but FAILED never becomes READY. This deliberately
retains bytes and sometimes names until a separately accepted decision exists.
See [the receipt contract and tests](upload-success-recovery.md).

## Receipt evidence needs a separate permanent abandonment decision

B4B uses the verified single-dispatch protocol to establish that the receipt's sole
trusted writer finished. The receipt does not authorize DELETE by itself. Fresh
FAILED identity/version/ancestry validation and an immutable V26 intent establish
permanent abandonment; a file-row trigger keeps all READY paths behind that fence.
Retaining the allocation through a non-cascading FK makes every replay address the
same old key and original binding. Metadata stays FAILED and its accepted snapshot
cannot drift, so later sealed folder membership remains valid.

A token/revision lease protects database outcomes after short transactions release
their locks. It cannot stop an expired worker's DELETE. Safety therefore comes
from the permanent decision and writer proof, while lease recovery supplies liveness.
DELETE-before-receipt crashes repeat the same abandoned target; a committed receipt
with lost acknowledgement skips the external effect on replay. Separate cleanup
receipts deliberately supply no folder-purge completion proof. Unknown writers
remain blocked regardless of age or HEAD. See [the restricted protocol and tests](known-success-upload-cleanup.md).

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

If PUT or input closure fails, best-effort marking as `FAILED` keeps diagnostic state without claiming absent bytes. B3 records successful PUT/close independently before finalization. Inaccessible destinations now remain blocked with retained UPLOADING metadata and bytes. An uncertain database outcome can already contain a committed receipt or READY row, so reload/replay replaces compensation; the foreground no longer deletes rejected upload objects.

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

## Component success does not establish a continuous purge pipeline

Phase 2E.3 joins real HTTP upload/trash/purge, scheduled outbox publication, RabbitMQ
ingestion, durable job execution and Garage absence in one bounded test. Event UUIDs
link each durable boundary; ACK observation checks job visibility from an independent
connection, while immutable byte/removal receipts establish completed effects.
Folder continuations retain one job across discovery, file deletion, leaf removal
and root finalization. Child-before-parent timestamps and unchanged control bytes
make the resulting state stronger evidence than a successful HTTP response alone.

Isolation must precede autonomous components: freshly CREATE a schema in the dedicated
test database, reject existing topology names, declare the queue before its consumer,
and verify empty eligible state before any poller/worker starts. Test-only absence-
checked key allocation preserves the real storage adapter and admission contract.
Failure cleanup owns exact resource identities, never a shared schema or bucket scan.
Closing an unacknowledged test channel produces actual redelivery; repeated production
transport sends verify duplicate ingestion without rearming authoritative events.
See [the executable verification and limitations](async-purge-verification.md).

## A published notification can still need durable-job recovery

Broker confirmation proves acceptance, not consumer transaction commit. Recovery
must start from the original committed event identity and retained purge intent,
never reconstruct a target from today's storage configuration. Reusing the same
event-ID insert and database uniqueness guard makes consumer/reconciler races
safe without resetting any existing job state. The insert outcome, rather than a
pre-insert existence check, distinguishes actual creation from a concurrent winner.

Bound both selection and each ingestion transaction. A keyset cursor must advance
over blocked records so malformed history cannot pin later work; wraparound and
restart safely retry retained records. Eligibility delay reduces delivery races
but does not replace deduplication. Distinguish per-event failures from failed
scans, retain evidence, and treat created-job counts as acceptance rather than
deletion. See [the recovery rules](missing-job-reconciliation.md).

## Durable boundary tests and actual process loss are different evidence

A latch test proves a chosen interleaving, but only a separate application JVM can
lose all its local state independently of the verifier. Phase 2E.5A keeps that JVM
on the test classpath, reuses fresh schema/topology boundaries, and journals only
absence-checked disposable allocation UUIDs before PUT. Receipts and checkpoints
still come from production transactions. Test advice must wrap the transaction
proxy: pausing before commit holds locks and does not test the intended window.

Windows Java may omit process arguments. A PID alone is insufficient ownership
proof. The harness combines the launched Process/start instant, executable,
matching run-token journals and a fresh live child challenge. A one-use permit
is tied to that exact identity and requires explicit user approval. Cooperative
release verifies harness wiring and retained evidence; it cannot be reported as
real crash verification. Recovery waits persisted leases/backoff and natural
reconciliation age rather than rewriting clocks or progress. Native broker
channel closure is required for the redelivery check: returning a cached Spring
channel may leave the delivery unacknowledged without requeuing it.

The authorized real-process matrix later passed all seventeen file/folder crash
boundaries. Its first INTENT attempt safely failed before permit issuance: observing an
empty outbox does not prevent a commit between that SELECT and claimNext. The
test must hold the first claim until the intent commits, then signal the boundary
before invoking the claim transaction. This changes test synchronization only.
Original receipts/checkpoints and fresh fencing tokens then proved recovery
without editing leases, retries or progress. Independent absence checks after
cleanup complement success markers. These results cover one application JVM;
they do not imply broker/VM restart, multi-node or production-load resilience.

## One database admission is not a physical-request counter

Phase 2E.5B4A joins real PostgreSQL admission and receipt transactions to the
production SDK/Apache client and a controlled HTTP receiver. The receiver counts
parsed PUT requests separately from completed bodies and accepted effects. A
single adapter method call could otherwise conceal a retry or redirect. The
contract needs all three pieces: one committed admission, one production PUT call
after it, and a client/transport that does not replay that call. A concurrent loser
must fail admission while the winning PUT is held at the receiver.

A timeout or missing response can coexist with accepted bytes and no committed
receipt. Successful stream closure must precede receipt persistence; failed close
deliberately leaves the same conservative uncertainty. Receipt rollback and lost
commit acknowledgement also differ: throw inside the real transaction to test
rollback, and outside its returned commit to test authoritative reload. Neither
case permits re-PUT or compensating deletion. Finite direct-connection tests do
not certify independent proxies, external writers or storage fencing. See the
[request counts and evidence boundary](single-put-dispatch-verification.md).

## Cancellation acknowledgement and cleanup proof are separate facts

M4 permanently chooses abandonment before granting one backend abort permission.
Its immutable upload ID, allocation binding and mutually exclusive completion
decision identify what may be aborted. A lost response remains uncertain across
restart: retrying abort or inspecting negative evidence cannot reconstruct the
missing acknowledgement. A successful HTTP 204 can complete the control-plane
cancellation while an already accepted part still returns 200 afterward, as the
live production-adapter gate demonstrated. Neither fact grants physical purge.

Expiry is an authorization decision using PostgreSQL time after lock acquisition;
it needs no autonomous storage action. A final authorization check after signing
also matters: cancellation can commit between the original part registration and
URL construction. Rejecting that batch preserves a clear response-authorization
point, while acknowledging that already delivered or in-transit capabilities
cannot be revoked by a database transaction. Retention and blocked purge are
explicit costs of preserving uncertainty. See the [M4 runbook](multipart-cancellation-expiry.md).

## M5: logical removal is not physical reclamation

A cancelled upload can safely release its display name because a future upload
gets a new immutable file/key allocation. That says nothing about outstanding
Garage requests. Reusing FAILED and the existing unique index keeps namespace
behavior simple; retaining cancellation/session identity preserves recovery facts.

A permanent folder intent already prohibits restore and new upload capabilities.
Sealed exact membership can therefore authorize removal of unfinished file metadata
without waiting for impossible Garage quiescence proof. A distinct immutable logical
receipt preserves this decision across checkpoints and crashes. It must never be
represented as DELETE or never-written proof: a previously permitted completion
can still produce an object after the folder disappears. Keep that object tracked.

READY is different: its positive completion receipt and permanent sole dispatch
identify a completed object, and permanent key nonreuse makes repeated exact-key
DELETE safe. An accepted late part returned 200 after deletion in the M5 live test;
the completed key stayed absent in that interleaving. This establishes neither
physical block reclamation nor a universal multi-node guarantee. Preserve the
boundary in APIs, SQL proof guards and operations documentation. See [M5](multipart-purge-compatibility.md).
