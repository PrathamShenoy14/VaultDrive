# VaultDrive architecture

Last reconciled with the repository on 2026-10-13.

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
- PostgreSQL schema managed by Flyway migrations V1-V15. Hibernate validates the schema (`ddl-auto=validate`) rather than creating it.
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

A pending-purge folder makes every descendant ineligible for restore, move, upload reservation/finalization, file purge request, or other metadata mutation. Folder restore rejects its own pending purge with `404 Not Found` after the exclusive owner hierarchy lock and fresh pessimistic Trash-row read, before any mutation or root fallback. Folder and file restore explicitly check pending-purge ancestry before ordinary inaccessible-parent root fallback. V11's `chk_folders_purge_requires_deleted` remains the final database guard against restoring pending-purge metadata.

Accepted file and folder requests append a version-1 purge-intent event to `outbox_events` in the same JPA transaction through a mandatory-transaction writer. Business state and event commit or roll back together, including after flush. Ownership/eligibility rejection and repeated requests append no event. A folder request emits one root intent, not a subtree event list. Phase 2A supplies durable jobs, Phase 2B ingestion and Phase 2C bounded scheduling/renewal. Phase 2D.1 adds immutable event-ID targets and a real file byte-purge handler under the accepted trusted-writer assumption. Phase 2D.2 Slice 2 adds shared byte receipts and conservative standalone metadata finalization. Legacy unknown bindings fail closed. Slices 4 and 5 add folder discovery/file byte execution. Slice 6 adds bottom-up folder metadata removal and durable FINALIZED proof; missing-job backfill and operational metrics remain planned. See [file purge](file-purge.md).

### Asynchronous processing — Partially implemented

ADR-0008 selects a PostgreSQL transactional outbox, RabbitMQ work notifications, PostgreSQL-backed durable job state, idempotent workers, and scheduler-driven recovery. Purge-request API transactions atomically persist business changes and outbox events; the opt-in publisher delivers committed notifications and recovers expired publication claims through database polling. Phase 2A adds job claiming/recovery primitives; Phase 2C schedules these for registered handler types. Phase 2D.1 supplies file byte purge; Phase 2D.2 Slices 4 and 5 supply folder discovery and file byte execution. Slice 6 adds folder metadata removal; missed-notification/job backfill remains planned. Database polling is a recovery and publishing mechanism within this design, not an alternative to RabbitMQ.

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

`files.version` supplies optimistic concurrency control between compatible shared-lock file mutations. Rename/move/trash use JPA optimistic updates; upload `UPLOADING -> READY|FAILED` changes use an explicit expected-status and expected-version conditional update. A stale same-file write fails with HTTP `409 Conflict` rather than overwriting another committed mutation. Restore and permanent-delete request use the same eligible-row pessimistic lock, so exactly one can consume a file's restorable Trash state. Restore name allocation is serialized per destination while different destination namespaces remain independent.

The advisory key derivation and staged migration constraints are recorded in ADR-0007. Folder operations for one owner remain serialized, key collisions can conservatively serialize different owners, database uniqueness constraints remain authoritative, and no throughput claim is made.

## Test evidence

The repository contains unit tests, MockMvc controller tests, Spring Security integration tests, PostgreSQL repository tests, folder concurrency integration tests, and a live S3-compatible storage integration test. PostgreSQL coordinator tests cover hierarchy lock compatibility/blocking, destination namespace lock compatibility/blocking, transaction release, and rejection outside a transaction. PostgreSQL races cover file move versus folder trash, file rename versus folder trash, optimistic same-file mutation conflicts, upload reservation versus folder trash, folder trash in the transfer gap before READY, competing finalization transitions, both file restore/permanent-delete winner orders, restore versus folder trash/move, same-name restores, folder purge request versus child/file restore, folder move, upload reservation, and both parent/child purge winner orders. Upload unit tests also cover known destination rejection cleanup, cleanup failure, and uncertain finalization behavior. Permanent-delete requests retain controller tests for accepted, not-found, and unauthenticated outcomes plus service, repository, constraint, and metadata transition coverage. The presence of a test is evidence of intended coverage, not proof that it passed on every machine; current verification results belong in the task/commit report.

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

- Remaining ADR-0008 async implementation: automated historical finalization recovery, missed-job/historical-intent backfill, outbox/job/quarantine retention, replay/reset tooling and operational tuning. File byte purge and conservative standalone metadata finalization are implemented under the accepted trusted-writer assumption; production load, real process-kill/restart and multi-node failure experiments remain unverified.
- Remaining file-side coordination: reconciliation and future direct-upload writer fencing.
- Reconciliation for stale `UPLOADING`, `FAILED`, orphaned objects, and uncertain finalization.
- Audited legacy storage-identity recovery and verified quiescence for admitted
  uncertain upload writers; these files still block automatic folder finalization.
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
