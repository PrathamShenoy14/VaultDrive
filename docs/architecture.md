# VaultDrive architecture

Last reconciled with the repository on 2026-10-19.

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

### Sharing M1 — grants and Viewer access

V32 adds immutable-target PostgreSQL Viewer/Editor grants, owner-only management
and bounded current-state permission resolution. Direct and inherited folder
grants use the strongest role; both collaborator roles support metadata,
folder/file listings and backend READY downloads. Shared with me filters
authorization before pagination, and resource responses redact inaccessible parent
references. Exclusive owner hierarchy coordination serializes grant mutations with
existing lifecycle paths; reads bind metadata, ancestry and grants in one statement
snapshot. Trash suspends access, restore/move recompute inheritance, and pending
purge blocks sharing changes. Storage evidence and worker defaults are unchanged.
See [M1 APIs, races, migration and verification limits](sharing-m1.md).

### Sharing M2: Editor content operations

Editors create subfolders owned by the destination owner, rename directly or
inherited editable resources, and trash content only when its immediate parent
is editable. Root creation/listing/Trash remains actor-scoped; directly shared
entry points cannot be trashed from a private or null parent. Moves, restore,
purge, grants and legacy upload remain owner-only. Mutation DTOs redact
private parents; Sharing M3 below extends Editor capabilities with multipart upload.

Mutations use the resource owner's existing exclusive folder/shared file hierarchy
lock. Fresh scalar permission resolution occurs inside the mutation transaction;
file entities are refreshed before authorization and retain optimistic version
checks against concurrent moves/writes. Folder writes refresh the managed target
under exclusive coordination. Grant revocation/downgrade serializes with mutations.
M2 requires no migration, storage identity or multipart lifecycle change. M4 below
adds links; M5 below adds activity and M6 adds recipient notifications.
See [M2 behavior, races and verification limits](sharing-m2.md).

### Sharing M3: delegated multipart uploads

V33 backfills immutable session initiators and scopes idempotency to owner,
initiator and key. Editors reserve into effectively editable folders; files,
allocations and keys belong to the destination owner. Only the owner and currently
authorized original initiator may manage each session. Fresh owner-hierarchy
coordination gates every admission/dispatch and post-storage response, including
post-signing authorization. Already admitted storage facts are retained under owner
identity despite revocation; existing receipt/lifecycle guards still control READY.
No uncertain Create/Complete/Abort is repeated, and no physical cleanup proof is
inferred. See [behavior, migration and verification](sharing-m3.md).

### Sharing M4: secure file Viewer links

V34 adds immutable file/owner-bound links with unique SHA-256 secret hashes,
required bounded expiry and permanent revocation. Owners create/list/revoke links;
the secret is returned once. Two dedicated header-authenticated public GETs expose
minimal file metadata or a backend attachment stream. Every request resolves
current READY/lifecycle/ancestry and link validity after owner hierarchy coordination.
Trash suspends access; restore reactivates only eligible links. Public responses
redact private identity/hierarchy, use no-store headers and issue no presigned GETs.
Public rate/concurrency admission uses fixed-memory per-process bounds. Storage and
synchronous streaming occur after authorization commits; admitted streams may finish
after revocation. No storage, multipart or purge protocol changes are introduced.
See [API, security, admission and verification limits](sharing-m4.md).

### Sharing M5: permission activity history

V35 adds immutable source-level permission snapshots for grant creation, role
change/revocation and file-link creation/revocation. Mutation, activity and a v1
outbox pointer commit atomically under the existing exclusive owner hierarchy
lock. No-ops/stale or failed requests add no activity. Historical names, direct
roles and recipient identity survive later changes and resource deletion.

The JWT history feed filters immutable ownership before count/pagination, with
optional exact resource/type/event filters and deterministic timestamp/UUID order.
Collaborators and link holders see no history for another owner's resources;
history supplies no current resource authority. No ancestry or link secrets enter
events. Permission outbox events use a separate quorum queue/routing key and are
excluded from purge recovery. M6 below consumes this route. No frontend is added.
See [schema, API, visibility and verification](sharing-m5.md).

### Sharing M6: in-app recipient notifications

V36 persists recipient-specific grant creation/change/revocation notifications
referencing immutable M5 activity. Database uniqueness deduplicates event/recipient
delivery, including concurrent consumers, and guards identity and monotonic read
state. Owners and link holders receive no notifications; there is no descendant
fan-out. A separately opt-in permission consumer validates the existing dedicated
route, commits before manual ACK, retries database failures three times, and stops
unacked on exhaustion. Poison messages commit sanitized quarantine before ACK.
Permission mutations, purge/recovery routing and all worker defaults are unchanged.

JWT APIs list/count/read only the caller's rows. Scope precedes filters/count/page;
one statement snapshot gates display against the exact current direct grant
revision and live resource permission/lifecycle. Obsolete or inaccessible events
remain generic, including after revoke/regrant and resource deletion. Notifications
never authorize resource access. See [migration, API, operations and verification](sharing-m6.md).

### Multipart purge compatibility — M5

V31 separates namespace release, logical metadata disposal and completed-key byte
receipts. Cancellation/expiry atomically releases eligible names using FAILED;
identity and physical uncertainty remain retained. Sealed folder purge can remove
non-READY multipart metadata with an immutable logical-discard receipt and no
storage I/O. READY multipart uses the existing durable exact-key deletion path
with its original completion receipt/binding and all deletion barriers. Folder
FINALIZED does not promise reclamation of discarded multipart allocations.
No autonomous cleanup default changes. See [guarantees, recovery and verification](multipart-purge-compatibility.md).

### Multipart cancellation and expiry — M4

V30 adds retained cancellation/expiry intent and guarded progress, immutable full
identity and single-assignment abort dispatch. Owner JWT cancel/read/recovery APIs
preserve mutually exclusive V27 decisions. A recorded HTTP 204 or permanent
never-initiation allows 200 cancellation; uncertain/blocked results are 202 and
never grant another abort. Explicit bounded PostgreSQL-time expiry records
abandonment without Garage I/O; no autonomous execution exists. Final URL-response
authorization rejects cancellation/expiry that wins during signing. Folder barriers,
completion/READY evidence and allocation retention stay intact; M5 extends purge
and namespace behavior above. Acknowledgement supplies no writer-termination or
removal proof. See
[API, state, expiry and retention runbook](multipart-cancellation-expiry.md).

### Multipart completion and recovery — Phase 3E / M3

V29 adds a frozen ordered completion manifest, immutable decision/upload identity,
single-assignment dispatch identity and guarded progress, plus a separate immutable
multipart receipt. Complete/recover APIs validate authoritative bounded Garage
pages, freeze before dispatch, persist exact positive object evidence, then perform
fresh receipt-backed READY finalization. Unknown dispatches never repeat Complete;
explicit recovery uses at most one exact positive checksum/metadata HEAD, or no
Garage call when a receipt already exists. No transaction or hierarchy lock spans
network I/O. At the M3 boundary, V25/V26 and multipart purge fences were unchanged.
M5 adds the explicit proof paths above. M4 adds cancellation; autonomous cleanup
and browser UI remain planned. See the [completion/evidence/recovery contract](multipart-completion-recovery.md).

### Multipart part transfers — Phase 3D / M2A

V28 adds append-only initiation dispatch permission and immutable per-part exact
length/SHA-256 registration. Owner-authenticated session APIs reserve, initiate,
presign bounded UploadPart batches, refresh identical expectations, list bounded
Garage resume pages and read durable state. Only a newly committed dispatch
permission can initiate; INITIATING after uncertainty never grants another Create.
Exact upload ID binding commits before OPEN/URLs. Garage I/O and signing run outside
database transactions and hierarchy locks. Fixed M1 geometry, signed length/checksum,
fresh ancestry checks and all legacy/purge/READY fences remain mandatory.
M2A added no completion or READY; M3 and M4 extend it above. Browser and autonomous
cleanup remain planned. Nominal
five-minute URL lifetimes do not resolve the measured deployment clock skew.
See [endpoint, transaction and evidence contracts](multipart-part-transfers.md).

### Multipart session foundation — Phase 3C / M1

V27 adds immutable SERVER_PUT/DIRECT_MULTIPART allocation identity and retained
owner-scoped idempotent sessions. Internal PostgreSQL-only reservation atomically
creates UPLOADING metadata/allocation/session with fixed geometry and database
expiry. Hierarchy → file → allocation → session coordination protects permanent
initiation and mutually exclusive completion/abandonment decisions and monotonic
revision. OPEN requires a durable upload ID; M1 performs no Garage operation.
At the M1 boundary, database guards excluded multipart from V25/V26/B2 and all
READY/removal proofs; M3 and M5 add protocol-specific READY and purge paths.
M1 itself added no endpoint, dispatcher, part table or browser feature; M2A extends it above. See the
[M1 contract and limitations](multipart-upload-sessions.md).

### Garage multipart characterization — Phase 3B / M0

Test-only Garage 2.3.0 experiments now characterize presigned UploadPart,
checksum/signed-length enforcement, replay/replacement, concurrency, late requests,
completion response loss and paginated resume evidence. They introduce no direct
upload feature, session schema, endpoint or production behavior. Arbitrary
different-content same-part competition exposed a content/ETag mismatch; future
capabilities must bind immutable part SHA-256 and length. Browser CORS/TLS/clock
prerequisites remain unresolved. See the [capability matrix and evidence limits](garage-multipart-capabilities.md),
[Phase 3A proposal](direct-upload-architecture.md) and [ADR-0009](decisions/0009-direct-upload-capability-boundary.md).

### Stale-upload inventory — Phase 2E.5B1

An explicitly called internal service reports bounded PostgreSQL-only pages of aged
UPLOADING/FAILED reservations. It uses immutable UUID keysets, fresh read-only
statement snapshots and bounded ancestry/transaction/page work. Identity issues,
pending purge and admission evidence determine conservative classifications;
admitted uncertain writes remain retained. There is no scheduler, HTTP endpoint,
Garage call or business-state mutation. See [invocation, precedence, bounds and
verification](stale-upload-inventory.md). The separate B2 operation below mutates
only freshly validated never-admitted reservations. B3 below adds receipt-based
finalization; restricted B4B cleanup is described below. General stale-upload
cleanup remains blocked under the [recovery audit](stale-upload-recovery-audit.md).

### Never-admitted reservation expiry — Phase 2E.5B2

Explicit bounded UUID pages expire aged UPLOADING reservations only after shared
hierarchy -> fresh file FOR UPDATE -> allocation FOR UPDATE coordination. Current
ownership, exact retained identity/full binding, version, database age and active
ancestry are revalidated; pending purge defers unchanged. V20 permanent admission
preclusion, status/version-conditional FAILED transition and V24 immutable expiry
timestamp/resulting-version evidence commit atomically. Admission first blocks
expiry; expiry first rejects delayed admission before PUT. Allocation tombstones
and keys remain retained; no scheduler, API, job/outbox or Garage operation is added.
See [invocation, eligibility, races and verification](stale-upload-expiry.md).

### Durable PUT success and metadata recovery - Phase 2E.5B3

After the single admitted Garage PUT and successful stream closure, foreground
uploads independently commit V25 success evidence on the retained allocation,
then invoke the same PostgreSQL-only READY transaction as explicit recovery.
Immutable admission version/destination plus the original file/owner/full binding
identify the receipt; PostgreSQL generates its immutable timestamp. Historical
admissions receive no backfill. Hierarchy -> fresh file -> allocation locks protect
eligibility, ancestry, purge barriers and the existing status/version CAS.
Inaccessible destinations remain UPLOADING with a reported blocked disposition;
foreground compensating deletion is removed. FAILED is never resurrected.
Explicit UUID pages default to 20, cap at 100, advance past blockers and use
independent bounded transactions. No scheduler, HTTP endpoint or Garage recovery
call is added. The gap after PUT/close but before receipt commit remains uncertain.
See [design, invocation, crash windows and verification](upload-success-recovery.md).

### Single-PUT dispatch verification - Phase 2E.5B4A

Controlled loopback HTTP tests use the production SDK/Apache configuration and
real PostgreSQL admission/receipt transactions to count received PUT requests.
Success, 5xx, reset, read timeout, redirects and response loss exercise the
single-dispatch contract; concurrent admission and repeated HTTP uploads verify
one-time allocations and distinct retry keys. Receipt checks follow PUT return
and successful stream closure. This verifies an application protocol under trusted
writers, not storage-side fencing or proxy/external-writer behavior. See
[request counts, verification and limits](single-put-dispatch-verification.md)
and the [preserved B4 design audit](known-success-cleanup-design-audit.md).

### Restricted known-success cleanup - Phase 2E.5B4B

Explicit bounded UUID pages clean only receipt-backed FAILED uploads matching the
original V25 admission destination and exactly one FAILED version transition.
Fresh hierarchy/file/allocation validation rejects identity/binding drift,
inaccessible ancestry and all purge-owned candidates. V26 commits an immutable
accepted metadata snapshot and abandonment intent before any DELETE; a database
trigger permanently fences every READY path and preserves that snapshot. Retained
allocation identity, recoverable 30-second token/revision claims and immutable
deletion/absence receipts support explicit crash/retry replay. Claims fence database
outcomes, not delayed DELETE; safety depends on the B4A trusted sole-writer contract,
permanent abandonment and key nonreuse. No transaction or hierarchy lock spans
Garage I/O. UPLOADING, READY, historical and admitted-uncertain writes stay excluded;
later folder manifests remain unchanged and cannot consume this separate proof.
No scheduler, endpoint, metadata deletion, general garbage collection or autonomous
enablement is added. See [eligibility, state machine, invocation and limits](known-success-upload-cleanup.md).

### Platform and persistence — Committed

- Java 21, Spring Boot 4.1.1, Maven wrapper, Spring MVC, Spring Data JPA, validation, and Spring Security.
- PostgreSQL schema managed by append-only Flyway migrations V1-V37. Hibernate validates the schema (`ddl-auto=validate`) rather than creating it.
- Configuration comes from properties and environment variables. Secrets are not intended for source control.

### Authentication sessions, logout and profile

- Registration normalizes email addresses, enforces BCrypt's 72-byte input limit, hashes passwords, and relies on a case-insensitive PostgreSQL unique index.
- Login verifies BCrypt hashes and issues 30-minute HS256 access JWTs with unique UUID `jti` and session `sid` claims.
- V37 stores refresh families, SHA-256 refresh/CSRF hashes, consumed-token history and indexed expiring jti revocations in PostgreSQL. Secure HttpOnly SameSite=Strict cookies carry 256-bit opaque refresh tokens; session-bound synchronizer headers protect refresh/logout and a custom login header prevents simple-request login CSRF.
- Family row locks serialize rotation, replay and logout across instances. Reuse revokes only its family; logout atomically revokes the family and presented access JWT. Every JWT request checks PostgreSQL and fails closed when state is unavailable. Explicit cleanup removes bounded expired jti batches.
- No server HTTP session is maintained. Registration, login, refresh and health are public routes with their documented credential/CSRF contracts. Sharing M4 retains its isolated public file-link GETs. Other endpoints require a valid bearer JWT.
- JWT subjects are user UUIDs. Issuer, signature and expiration remain validated. Authenticated profile GET/PATCH use explicit safe DTOs and validated display names.

See [endpoint contracts, security, operations and verification](authentication-sessions.md).
Not implemented: account-state controls, general API rate limiting, JWT key rotation,
asymmetric signing or refresh-history retention cleanup. Share-link revocation and
per-process public-link limits remain implemented; deployment requires HTTPS,
secret-safe logging and edge timeouts. No frontend or multipart browser changes are included.

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
- Upload reserves `UPLOADING` metadata and its permanent allocation, commits write admission, then streams bytes to S3 without a database transaction or hierarchy lock. After successful stream closure, an independent receipt commit precedes the short READY transaction.
- Finalization freshly locks the owned file and original allocation after shared hierarchy coordination. It validates the retained receipt/admission identity, expected UPLOADING version, lifecycle, destination ancestry and conflicting purge evidence before the existing CAS. Matching READY resolves replay. An inaccessible destination stays blocked with unchanged metadata and retained bytes. Receipt or finalization uncertainty never causes a repeated PUT or compensating DELETE. Historical admitted uploads without receipts remain unrecoverable in B3.
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

A pending-purge folder makes every descendant ineligible for restore, move, upload reservation/finalization, file purge request, or other metadata mutation. Folder restore rejects its own pending purge with `404 Not Found` after the exclusive owner hierarchy lock and fresh pessimistic Trash-row read, before any mutation or root fallback. Folder and file restore explicitly check pending-purge ancestry before ordinary inaccessible-parent root fallback. V11's `chk_folders_purge_requires_deleted` remains the final database guard against restoring pending-purge metadata.

Accepted file and folder requests append a version-1 purge-intent event to `outbox_events` in the same JPA transaction through a mandatory-transaction writer. Business state and event commit or roll back together, including after flush. Ownership/eligibility rejection and repeated requests append no event. A folder request emits one root intent, not a subtree event list. Phase 2A supplies durable jobs, Phase 2B ingestion and Phase 2C bounded scheduling/renewal. Phase 2D.1 adds immutable event-ID targets and a real file byte-purge handler under the accepted trusted-writer assumption. Phase 2D.2 Slice 2 adds shared byte receipts and conservative standalone metadata finalization. Legacy unknown bindings fail closed. Slices 4 and 5 add folder discovery/file byte execution. Slice 6 adds bottom-up folder metadata removal and durable FINALIZED proof; Phase 2E.4 adds bounded missing-job recovery and per-page counters; broad historical backfill and general metrics remain planned. See [file purge](file-purge.md).

### Asynchronous processing — Partially implemented

ADR-0008 selects a PostgreSQL transactional outbox, RabbitMQ work notifications, PostgreSQL-backed durable job state, idempotent workers, and scheduler-driven recovery. Purge-request API transactions atomically persist business changes and outbox events; the opt-in publisher delivers committed notifications and recovers expired publication claims through database polling. Phase 2A adds job claiming/recovery primitives; Phase 2C schedules these for registered handler types. Phase 2D.1 supplies file byte purge; Phase 2D.2 Slices 4 and 5 supply folder discovery and file byte execution. Slice 6 adds folder metadata removal; Phase 2E.4 adds opt-in aged-PUBLISHED missing-job reconciliation; broad historical backfill remains planned. Database polling is a recovery and publishing mechanism within this design, not an alternative to RabbitMQ.

RabbitMQ development infrastructure is deployed on the Ubuntu VM as recorded in [Phase 1A setup instructions](rabbitmq-development.md). AMQP and management bind to VM loopback and Windows accesses them through SSH forwarding. Phase 1B adds outbox persistence and atomic purge-request insertion. Phase 1C adds opt-in scheduled publication: PostgreSQL due-row locking and durable token/lease claims, persistent RabbitMQ messages to a durable direct exchange/quorum queue, correlated confirms with mandatory-return checks, bounded exponential retries, and expired-claim recovery. Claims commit before broker I/O; only an unexpired matching token can record completion. [ADR-0008](decisions/0008-transactional-outbox-rabbitmq.md) records the at-least-once contract and [publisher instructions](outbox-publishing.md) describe configuration/recovery. Phase 2D.1 adds file byte deletion only; historical-intent backfill remains absent. Publisher polling, consumer startup and worker scheduling are independently disabled by default.

Phase 2A adds `background_jobs` and a minimal JPA service/repository. The primary key is the stable event UUID, so duplicate creation preserves even terminal progress; mismatched type/aggregate identity is rejected. Supported jobs are file/folder purge references without payload or cascading foreign keys. Job execution state is independent from outbox delivery state: `PENDING -> RUNNING -> SUCCEEDED`, with retry/continuation to `PENDING` and terminal `FAILED`. Slice 3 separates monotonic claims from ten failed/abandoned executions; the 30-second lease, capped one-to-60-second exponential failure backoff and database-clock deadlines remain. Claim/recovery uses ordered job-row `FOR UPDATE SKIP LOCKED`; completion/retry/continuation takes the job row lock before checking the live token/lease. No hierarchy/resource locks or external I/O enter job-only transactions. Phase 2C drives supported-type recovery and adds fenced renewal. Tokens fence database writes, not stale external effects. See [job state and crash recovery](background-jobs.md).

Phase 2B consumes existing v1 purge notifications with manual acknowledgements. It validates strict envelope identity/version/required fields against committed immutable outbox intent, then creates/finds the event-ID job in one short transaction and ACKs only after commit. It never requires outbox `PUBLISHED`, mutates resource metadata, claims jobs or calls Garage. Three database attempts with one-/two-second delays precede fatal container stop with unacked work; explicit restart permits broker redelivery without an immediate requeue loop. Invalid messages commit a sanitized V15 `notification_quarantine` fingerprint/event-ID/reason record before ACK. No raw payload or exception text is retained. Consumer startup is opt-in, and existing broker topology is unchanged. See [consumer configuration and recovery](purge-consumer.md). Retained outbox records are required for validation; missing-source notifications quarantine even if an existing job survives retention.

Phase 2C adds a worker foundation with two queue-free fixed-delay control loops, bounded handler/renewal pools and capacity reservation before claims. Defaults are two job slots, five-second polls, ten-second renewal scans, five-minute max runtime and five-second shutdown grace. Renewal uses the fresh job-row lock, matching token and PostgreSQL-clock lease validity; it extends expiration plus the abandoned-attempt backoff without changing attempts. Monotonic local lease/runtime limits and cancellation monitoring remain independent of slow renewal database calls. Context closure stops admission, permits bounded graceful completion/renewal, then interrupts unfinished work and lets claims expire. Registered handlers run outside transactions and must return explicit success. The file and folder handlers are registered, so explicit enablement can execute eligible file and folder jobs; Slice 6 completes eligible folder metadata removal and durable finalization. Consumer/publisher protocols are unchanged. See [worker configuration and limitations](job-workers.md).

Phase 2D.1 adds V16 retained `file_storage_objects` key allocations and immutable `file_purge_targets` indexed by the existing event/job UUID. New uploads reserve their namespace/endpoint/region/bucket at metadata creation and consume one write invocation before Garage I/O. Legacy allocations have no inferred binding. New eligible purge requests copy the upload binding/key and expected pending-file version/timestamp atomically with intent and the unchanged v1 event. The handler validates owned pending READY state in a short hierarchy/file-row transaction, checks the live claim, then deletes only the persisted bucket/key through a matching configured storage client. Timeouts are bounded, SDK retries disabled, object absence is success, and uncertain failures use durable retries. Garage does not enforce nonreplacement: independent overwrites, alias/endpoint reassignment and late external effects remain accepted trusted-writer/operator risks. [File purge and recovery](file-purge.md) states the contract and limitations.

Phase 2D.2 Slice 2 adds V17 shared `file_purge_work`, unique by file ID, with immutable storage/lifecycle identity and separate monotonic byte-deletion and metadata-finalization receipts. Resource transactions lock hierarchy → fresh file → work → job; PostgreSQL-clock token/lease fencing rolls back stale writes. Byte deletion remains outside transactions. Metadata removal and its receipt commit atomically only with a byte receipt and matching owner/folder/key/version/READY/lifecycle timestamps. Pending folder ancestors defer removal until Slice 4 sealed membership exactly matches the standalone work. Retries skip DELETE when byte proof exists; missing metadata alone is never success. Explicit bounded historical-success recovery imports matching V16 outcomes without Garage calls or job resets. SUCCEEDED remains byte-only. Allocations, targets, work and job identities survive removal. Slices 4 and 5 add sealed folder manifests and folder byte execution; automated finalization recovery remains planned.

Phase 2D.2 Slice 3 implements V18 continuation accounting and a MANDATORY-transaction
checkpoint CAS, acquired last after resource/work locks. Useful business work and
its checkpoint must commit atomically; the next live CONTINUE requires a revision
newer than this claim's baseline and schedules the same job one second later.
No-progress yields consume a failure; stale/expired tokens change nothing. Crashes
retain checkpoints and charge abandonment once on recovery. Worker concurrency,
cancellation, runtime and shutdown bounds still apply; production file handlers
retain SUCCESS/RETRY. No folder handler or default enablement is added. Real
PostgreSQL migration/fencing tests, worker tests and Garage regressions passed;
see [the Slice 3 verification record](folder-purge-design.md#slice-3-continuation-foundation)
for executed totals and the environment-gated RabbitMQ skips.

Phase 2D.2 Slice 4 adds V19 retained folder runs, per-folder UUID keyset frontiers
and immutable file membership snapshots. Request intent/outbox/run commit atomically;
legacy initialization requires the original outbox/root intent match. One page of
1-100 members commits with its V18 checkpoint under exclusive hierarchy coordination
and the final job fence. All scans must complete before sealing DISCOVER -> DELETE_FILES.
Active/trashed descendants and every upload status are represented without recursive
Java traversal. V17 standalone finalization requires exact sealed membership beneath
pending ancestors. The explicit discovery adapter returns CONTINUE. Slice 5 invokes it from a
registered handler and adds folder-led byte deletion. Slice 6 adds folder removal; automatic
historical finalization recovery remains planned.
See [Slice 4 schema, bounds and recovery](folder-purge-design.md#slice-4-durable-paginated-discovery).

## Phase 2D.2 Slice 5 implementation boundary

The registered folder handler executes sealed DELETE_FILES in deterministic pages
of at most twenty members. Short hierarchy/resource/allocation/work transactions
validate exact manifest lifecycle and storage identity; V17 work coordinates
standalone jobs without resetting or requiring their success. Garage DELETE runs
outside PostgreSQL, using the existing bounded adapter and trusted-writer contract.
Byte receipts/member checkpoints commit atomically under the current token and
PostgreSQL-clock lease. V20 adds retained member dispositions and permanent
write preclusion/never-written proof for verified unconsumed allocations. V21 guards phase order and requires original
allocation preclusion before never-written proof. Unsafe
admitted writers, unknown bindings/targets and identity drift remain blocked.

Successful pages use V18 CONTINUE; unchanged blockers/Garage failures use bounded
retries. Only complete validated member proofs permit the fenced DELETE_FOLDERS
checkpoint. Slice 6 completes folder metadata removal and durable root finalization;
automatic repair/reconciliation remains pending. Standalone finalization
can still use exact sealed membership at this phase. Enablement defaults, RabbitMQ
topology, v1 events, infrastructure and credentials are unchanged.
See [workflow, failure handling and recovery](folder-purge-design.md#slice-5-folder-led-file-byte-deletion).

## Phase 2D.2 Slice 6 metadata finalization

V22 retains immutable folder-removal receipts, sealed manifest counts and run
FINALIZED timestamp. DELETE_FOLDERS uses the shared V17 file finalizer after exact
lifecycle/storage/proof/barrier validation, including coordinated never-written
proof. Pages finalize at most twenty files, then remove at most twenty current
leaves in UUID order. Complete discovery, finalized direct files, child receipts
and absence of actual children/files precede each removal; non-cascading FKs remain.
The root is removed last with its receipt, FINALIZED state and checkpoint in one
transaction. Only validated retained FINALIZED proof allows missing-root success.

Exclusive hierarchy -> fresh resource rows -> work/progress -> final job fencing
preserves atomic bounded removal/checkpoint transactions. Successful incomplete
pages CONTINUE; stale tokens, expired leases, bad revisions, corruption and missing
proof roll back. Committed child progress resumes after abandonment, and root
completion loss replays proof without another removal/checkpoint. Standalone
finalization shares receipts and never has its job reset. No Garage I/O occurs
in this phase; all disabled defaults and existing APIs/contracts remain.
See [Slice 6 bounds and recovery](folder-purge-design.md#slice-6-bottom-up-metadata-removal-and-durable-finalization).

## Current consistency and concurrency model

PostgreSQL partial unique indexes are the final defense against duplicate active names. Services also perform pre-checks for clearer errors.

Folder create, rename, move, trash, restore, and permanent-delete request acquire an exclusive owner-scoped PostgreSQL transaction advisory lock before protected reads. File rename, move, trash, restore, permanent-delete request, upload reservation, and upload finalization acquire the matching shared lock before loading current file or ancestor state, then validate and write inside the same short transaction. Restore additionally takes a destination-scoped exclusive advisory lock before collision naming. The coordinator requires an active transaction and uses the transaction's JPA connection, so PostgreSQL releases locks on commit or rollback. Garage upload and cleanup calls occur outside these transactions and locks.

Phase 2E.2 explicitly disables Open EntityManager in View (`spring.jpa.open-in-view=false`), so service transactions own their persistence contexts. With the previous implicit OSIV default, the real HTTP service transaction was active and its native Hibernate session was joined, but Spring ORM 7.0.9's application-managed EntityManager wrapper reported `isJoinedToTransaction()=false`; the hierarchy guard therefore rejected requests with 500. The confirmed wrapper/holder mismatch, sanitized true/false comparison and supported configuration are recorded in [ADR-0007](decisions/0007-concurrency-locking.md#phase-2e2-http-transaction-joining). Coordinator guards, service transaction boundaries, authorization and lock order are unchanged. Explicit OSIV re-enablement remains unsupported on this dependency set.

`files.version` supplies optimistic concurrency control between compatible shared-lock file mutations. Rename/move/trash use JPA optimistic updates; upload `UPLOADING -> READY|FAILED` changes use an explicit expected-status and expected-version conditional update. A stale same-file write fails with HTTP `409 Conflict` rather than overwriting another committed mutation. Restore and permanent-delete request use the same eligible-row pessimistic lock, so exactly one can consume a file's restorable Trash state. Restore name allocation is serialized per destination while different destination namespaces remain independent.

The advisory key derivation and staged migration constraints are recorded in ADR-0007. Folder operations for one owner remain serialized, key collisions can conservatively serialize different owners, database uniqueness constraints remain authoritative, and no throughput claim is made.

## Test evidence

The repository contains unit tests, MockMvc controller tests, Spring Security integration tests, PostgreSQL repository tests, folder concurrency integration tests, and a live S3-compatible storage integration test. PostgreSQL coordinator tests cover hierarchy lock compatibility/blocking, destination namespace lock compatibility/blocking, transaction release, and rejection outside a transaction. PostgreSQL races cover file move versus folder trash, file rename versus folder trash, optimistic same-file mutation conflicts, upload reservation versus folder trash, folder trash in the transfer gap before READY, competing finalization transitions, both file restore/permanent-delete winner orders, restore versus folder trash/move, same-name restores, folder purge request versus child/file restore, folder move, upload reservation, and both parent/child purge winner orders. Upload unit tests also cover known destination rejection cleanup, cleanup failure, and uncertain finalization behavior. Permanent-delete requests retain controller tests for accepted, not-found, and unauthenticated outcomes plus service, repository, constraint, and metadata transition coverage. The presence of a test is evidence of intended coverage, not proof that it passed on every machine; current verification results belong in the task/commit report.

Phase 2E.2 adds embedded-server HTTP tests with real JWT security, services and committed disposable fixtures in `vaultdrive_test.hierarchy_http_test`, without test-owned request transactions. The production-default suite covers folder/file mutation and restore/purge responses, unchanged rows on rejection, rollback after real outbox insertion, DTO reads and real Garage upload/download. Latch-controlled requests verify both restore/purge winner orders and both shared-file-move/exclusive-folder-trash orders, including PostgreSQL advisory-lock waits and fresh post-wait validation. Separate explicit OSIV false and true controls record transaction-active, injected/native joined and EntityManager identity state; the true control intentionally reproduces the wrapper failure and is not a supported deployment setting. All asynchronous application flags stay false.

Phase 1B PostgreSQL tests cover file/folder outbox commit, rollback after both flushes, real insertion-constraint failure, independent-transaction visibility, unauthorized/active/repeated request rejection, JSONB payload round-tripping, and invalid delivery/payload metadata. Existing file restore-versus-purge and parent/child folder-purge races also assert that only accepted intent emits an event. These tests establish persistence guarantees; publisher delivery coverage is added separately in Phase 1C.

Phase 1C adds simultaneous same-resource purge requests with both first-request commit/rollback outcomes, PostgreSQL concurrent publisher/skip-locked tests, release of row locks before network I/O, token/expiry fencing, expired-claim backoff, bounded exhaustion, and replay. Transport tests inject negative confirms, timeouts and mandatory returns. Isolated live RabbitMQ tests cover positive confirms, persistent messages, mandatory returns, and replay after actual broker acceptance. Outbound-I/O hardening uses non-blocking NIO with a bounded frame queue and enqueue timeout, bounded I/O executors, and interruptible polling shutdown. Local client-queue/socket tests and PostgreSQL tests cover saturation recovery, subsequent event progress, stale-lease rejection during a stall, and bounded scheduler shutdown; live broker tests exercise NIO. Consumer ingestion coverage is added in Phase 2B below. Final executed test results belong in the task report.

Phase 2A PostgreSQL tests cover concurrent duplicate job creation, one-job competing claims, skip-locked selection, released row locks, claim rollback and uncommitted creation, token/expiry fencing before and after recovery, completion waiting for a lock, persisted attempt-based backoff, known/abandoned exhaustion, terminal replay, type filtering and schema guards. They validate persistence and recovery primitives only; no execution worker, notification-to-job recovery or physical purge is tested. Executed results belong in the task report.

Phase 2B tests cover strict message validation, source/identity checks, commit-before-ACK, rollback on commit failure, bounded retry, quarantine persistence/constraints, terminal duplicate replay, uncertain ACK and disabled startup. Live isolated-vhost tests exercise publisher-to-consumer delivery for both event types, duplicate notifications, malformed/unsupported version quarantine, three failed database attempts followed by container stop and explicit restart/redelivery, and a committed job with deliberately lost ACK. The tests validate ingestion only, not storage execution or missed-notification backfill.

Phase 2C PostgreSQL tests cover concurrent scheduler claims, capacity reservation, handler transaction boundaries, explicit/null outcomes, renewable lease/deadline invariants, stale-token and lock-wait expiry rejection, abandoned-claim and timed-out-task recovery, stalled renewal isolation and bounded shutdown. Configuration tests exercise disabled startup, absent/duplicate handlers, invalid timing/capacity and actual Spring context closure. Handlers are test-only; no physical storage execution or real process-kill/load evidence is added.

Phase 2D.1 adds real PostgreSQL/Garage tests using unique disposable owner/file keys
for byte deletion, absence, successful-effect replay, retries, cancelled/replaced
claims, stale in-flight effects, fenced worker completion, transaction/lock release,
competing writes, immutable identity guards, target/outbox rollback/visibility and
legacy migration. Adapter tests cover binding drift and provider classifications;
a local stalled S3 server tests finite I/O. Existing Garage tests use unique keys.
These tests do not establish storage-side nonreplacement or process-kill/load behavior.

Phase 2D.2 Slice 2 adds byte-receipt/finalization rollback and replay boundaries,
duplicate concurrent execution, live-token/lock-wait expiry fencing, missing
metadata with/without byte proof, exact identity/lifecycle mismatch rejection,
permanent allocation retention, bounded historical success recovery and both
folder-purge/finalization winner orders. Real PostgreSQL and disposable Garage
fixtures establish durable state and object outcomes. See executed results in
[the folder purge slice record](folder-purge-design.md).

Slice 4 PostgreSQL discovery tests cover a 186-folder/392-file manifest with
60 levels, multiple page/reclaim cycles beyond ten healthy continuations, all
upload statuses, separately trashed resources, atomic request/run rollback,
legacy-event validation, stale/replayed/expired checkpoints, corruption/cycles,
complete-only sealing and both standalone-finalization winner orders. Barrier,
cancellation/runtime and final job-lock-wait expiry tests assert durable state.
These new fixtures invoke no Garage I/O. See the executed results in
[the Slice 4 record](folder-purge-design.md#slice-4-durable-paginated-discovery).

Slice 5 adds 46 PostgreSQL/Garage cases covering bounded byte execution and
continuations, shared/in-flight standalone work, never-written admission/proof,
unsafe/legacy blockers, crash replay, drift/storage failures, duplicate completion,
stale/expired leases and atomic phase checkpoints. Fixtures use only disposable
objects and assert preservation of unrelated bytes. Final verification: 220 focused
tests passed; the full suite ran 620 tests with 613 passed, seven gated live broker
skips and no failures/errors. See [Slice 5 results and recovery](folder-purge-design.md#slice-5-folder-led-file-byte-deletion).

Phase 2D.2 Slice 6 adds 30 PostgreSQL/Garage integration cases for bottom-up
metadata removal, durable root proof, migration backfill, bounded continuation,
crash/rollback replay, duplicate/stale fencing, both standalone finalizer winner
orders, FK/unexpected-descendant protection and missing/corrupt records. The final
safely runnable suite ran 650 tests: 643 passed, seven environment-gated live
RabbitMQ checks skipped, zero failures/errors. See [Slice 6 verification and
recovery](folder-purge-design.md#slice-6-bottom-up-metadata-removal-and-durable-finalization).

## Known gaps and pending decisions

- Remaining ADR-0008 async implementation: automated historical finalization recovery, legacy/blocked historical-intent backfill, outbox/job/quarantine retention, replay/reset tooling and operational tuning. File byte purge and conservative standalone metadata finalization are implemented under the accepted trusted-writer assumption; single-application-JVM crash/restart boundaries are verified by Phase 2E.5A; production load, broker/VM restart and multi-node failure experiments remain unverified.
- Remaining file-side coordination: reconciliation and future direct-upload writer fencing.
- Historical admitted stale `UPLOADING`, `FAILED` and orphan cleanup remain unresolved. B2 implements never-admitted expiry; B3 recovers only freshly eligible UPLOADING with durable success receipts. PUT success before receipt commit remains unknown after a crash.
- Audited legacy storage-identity recovery and verified quiescence for admitted
  uncertain upload writers; these files still block automatic folder finalization.
- Notification retention/account erasure and replay tooling, versioning, client integration for direct/resumable transfers, quotas, malware scanning, general audit logs, observability, and client applications.
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

## Phase 2E.3 continuous isolated purge verification

[The continuous verification runbook](async-purge-verification.md) describes the
opt-in [HTTP-to-Garage harness](../backend/src/test/java/com/vaultdrive/e2e/AsyncPurgeEndToEndTest.java).
It establishes a newly owned UUID schema inside `vaultdrive_test` and UUID exchange/
quorum queue inside the existing `vaultdrive_outbox_test` vhost before starting the
application. Each asynchronous component has a pre-initialization gate checking
current database/schema/search path and empty outbox/job/resource tables. Queue
creation precedes consumer startup. Disposable Garage allocations are run-prefixed,
absence-checked and registered individually; no existing user object is eligible.

Real HTTP registration/login, upload/download, trash and permanent-delete requests
feed the scheduled production publisher, production consumer/ingestor and autonomous
worker with both real handlers. One event UUID ties confirmed publication, committed
job-before-ACK, byte receipts and successful execution together. The nested case
observes DISCOVER -> DELETE_FILES -> DELETE_FOLDERS -> FINALIZED, includes separately
trashed descendants, and verifies retained counts/proof, child-before-parent removal
and root-last finalization. An unrelated control subtree remains HTTP-readable.

Duplicate publication uses retained test events and the production transport;
closing a temporary unacknowledged receive channel forces actual broker redelivery.
Six successful ACKs, a drained isolated queue, exactly two unchanged terminal jobs
and unchanged receipts establish idempotent ingestion. PostgreSQL rejects attempted
receipt replacement in rolled-back test transactions. The ACK observer delegates
the real listener and observes basicAck after verifying independent job visibility;
it replaces no service behavior. Only the test key allocator changes object names.

Polling selects bounded scalar/proof fields and has query/deadline limits; shared
output excludes keys, payloads, tokens and credentials. Finally blocks stop async
work and clean only owned objects/topology/schema. Failed owned-object cleanup
retains the isolated schema and allocation identities for bounded recovery. Retained
proofs are asserted
before disposing of the test schema. Forced JVM termination can bypass cleanup and
requires bounded review of that exact run; no global cleanup facility is supplied.
Production flags, migrations, lock order, fencing, receipts and trusted-writer
assumptions are unchanged. This single-node happy-path/replay evidence adds no
process-kill, load, failover, unsafe-writer reconciliation or deployment claim.

## Phase 2E.4 bounded missing-job recovery

An independently opt-in poller recovers aged PUBLISHED outbox events with no
same-UUID job. It reuses strict consumer source validation and durable job
deduplication, then additionally requires retained V16 file target/allocation or
V19 folder run plus matching fresh committed resource intent. Quarantined,
unsupported and inconsistent sources remain blocked. V23 indexes bounded
published_at/id keyset pages; five-second statements/two-second lock waits bound
each independent transaction. Shared hierarchy -> fresh resource FOR SHARE ->
job identity insertion preserves existing worker fencing and changes no resource,
receipt or existing job. No broker/storage I/O occurs. Five-minute eligibility,
one-minute polls and twenty-event pages are conservative defaults, not measured
tuning. Per-page scanned/created/alreadyPresent/skipped/failed counters and
scanFailed contain no payload/key/credential labels. All four autonomous flags
remain false by default. PostgreSQL tests exercise both consumer winner orders,
pagination, invalid/quarantined/terminal history and timeout rollback; the Garage
control asserts retained bytes. See [eligibility, operation and limits](missing-job-reconciliation.md).

## Phase 2E.5A isolated process recovery harness

Test-classpath-only child JVMs now support deterministic pauses outside committed
production transactions at intent, confirm, ingestion/ACK, claim, discovery,
Garage DELETE, byte receipt, child removal and FINALIZED boundaries. Standalone
and nested-folder scenarios retain their original event/job identity, immutable
targets, receipts, fencing and durable checkpoints across restart. A separate
consumer-disabled case waits the default five real minutes before missing-job
reconciliation. Durable UUID allocation journals bound interrupted-run cleanup.
No production code, migration, lock order, API, topology default or autonomous
component default changes. See the [operational runbook](async-process-recovery.md).

The authorized real process-kill matrix passed all seven file and ten nested-folder
scenarios, with exact live ownership/isolation checks and one-use permits before
each termination. Original identities, targets, checkpoints, receipts, fencing,
duplicate handling and readable controls survived; both missing-job cases waited
the default five real minutes. A test-only pre-claim INTENT observation race found
before any kill was corrected and verified. All owned resources were cleaned.
See the runbook for per-boundary results, post-verification tests and limitations.
Broker/VM restart, multi-node failure, load and uncertain PUT recovery remain
outside this single-application-JVM evidence.
