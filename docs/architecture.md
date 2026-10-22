# VaultDrive architecture

Last reconciled with the repository on 2026-10-22. This is the current implementation map. Earlier phase records and ADR extensions preserve history; their original boundaries are not the current inventory.

## System and responsibility boundaries

VaultDrive is a Java 21 / Spring Boot 4.1.1 backend. PostgreSQL owns searchable metadata, authorization, authentication sessions, lifecycle intent, outbox/jobs and durable evidence. Garage 2.3.0 owns object bytes through `ObjectStorageService`. RabbitMQ carries at-least-once notifications for committed PostgreSQL work. Neither external system shares a metadata transaction.

```text
Client -- bearer/JSON control API --> Spring Boot --> PostgreSQL metadata/evidence
   |                                    |-- ObjectStorageService --> Garage
   `-- signed exact UploadPart PUT ----> Garage
                                        `-- outbox --> RabbitMQ --> ingestion
                                                                    --> PostgreSQL jobs/notifications
```

| Responsibility | Supported implementation |
|---|---|
| Registration, login, refresh/logout, profile | `auth`, `security`, `user`; BCrypt, bearer JWTs, PostgreSQL families/revocation |
| Metadata, Trash, restore, purge intent | `folder`, `file`, `hierarchy`; service transactions and PostgreSQL constraints |
| New uploads | Multipart session/transfer/completion/cancellation services; direct multipart only |
| Storage I/O/signing | `ObjectStorageService` / `S3ObjectStorageService`, immutable binding, SDK retries disabled |
| Resource authority/reads | `ResourceAccessRepository`, `ResourceReadService`, PostgreSQL resolver snapshots |
| Filename search/filtering | `search`; combined file/folder pages through the same PostgreSQL access resolver |
| Grants, links, activity, notifications | `sharing`, permission ingestion and isolated public-link security |
| Publication and ingestion | PostgreSQL outbox, confirmed mandatory RabbitMQ delivery, typed transactional ingestors and shared bounded ACK policy |
| Execution and recovery | PostgreSQL jobs, bounded worker/file/folder handlers, retained evidence and independently opt-in missing-job reconciliation |

The [consolidation audit](backend-consolidation.md) records removals, retained history, verification and deferred decisions. [ADR-0010](decisions/0010-backend-consolidation.md) records the consolidated boundaries.

## Persistence and schema

Flyway V1–V40 is append-only; Hibernate validates the schema. JPA manages users, folders, files, immutable outbox writes and short job/outbox entity transactions. Native EntityManager SQL implements PostgreSQL recursive authorization, scalar evidence reads, row locks, claims and conditional transitions. JdbcTemplate manages authentication sessions/revocations without a parallel JPA entity model. All use the configured datasource/transaction manager.

Services own transactions and `spring.jpa.open-in-view=false` remains required. SQL stays in its existing responsibility's transaction service or access repository. A blanket JPA/JDBC conversion would obscure locking and atomicity. Managed mutation targets are refreshed where required; status/version bulk CAS flushes/clears the context. Scalar worker reads avoid stale managed state; independent job/outbox transactions bound cache lifetime. Never save an older entity after native SQL changed that row.

PostgreSQL is the final invariant guard: case-insensitive email uniqueness, same-owner parent foreign keys, active sibling-name uniqueness with root NULL semantics, file versions, immutable allocation/dispatch identities, monotonic progress and proof-dependent removal. Application checks improve errors while constraints decide competing commits.

## Authentication and authorization

Registration normalizes email and enforces BCrypt's 72-byte input bound. Login/refresh issue 30-minute HS256 JWTs with unique `jti` and family `sid`. Seven-day absolute refresh families retain hashed 256-bit credentials, consumed history and hashed CSRF secrets. Family locking precedes fresh token reads. Reuse commits family revocation before returning an error; logout revokes the family and presented jti. Every bearer request checks PostgreSQL and fails closed.

Secure HttpOnly SameSite=Strict host-only refresh cookies are confined to the same-origin login/refresh/logout contract. Custom login headers and session-bound CSRF protect cookie mutations. Other APIs are bearer-only; profile records expose no credential state. See [authentication sessions](authentication-sessions.md).

Resource reads bind ownership, accessible ancestry, lifecycle and strongest direct/inherited Viewer/Editor role in one statement snapshot. Shared entry points filter authority before count/page and redact private parents. Mutation authorization is fresh under owner hierarchy coordination. Owners manage moves, restore, purge, grants and links. Editors have the specifically supported content operations and multipart admission into editable folders.

Authenticated filename search uses `SearchController` -> transactional `SearchService`
-> `ResourceAccessRepository` -> the existing V32 resolver. A possible-owner set
(actor plus owners with a current direct grant) and literal name predicate reduce
candidates; neither replaces effective authorization. Materialized accessible and
filtered sets bind redacted metadata, count and deterministic mixed-resource pages
to one statement snapshot. SQL filters cover type, exact MIME, inclusive size and
creation/modification bounds, and owned/shared scope. No Java tree traversal,
permission cache, copied descendant grants or storage calls are introduced.
V40 adds public `pg_trgm` and partial active-name GIN indexes. Short queries may
scan; transaction/statement/lock budgets bound work, without a performance claim.
See [the endpoint and validation contract](api-contract.md#filename-search-and-filtering-2026-10-22).

File Viewer links use hashed secrets in a dedicated header, isolated public GET security, uniform failures, no-store/no-referrer/nosniff and bounded per-process request/download admission. Downloads stream through the backend without presigned GETs. Admitted streams may finish after revocation. Immutable activity/outbox and recipient-isolated notifications survive resource changes without conferring authority. See [Sharing M1–M6](sharing-permissions-design.md).

## API contract consistency

[The current API contract](api-contract.md) defines fresh authorization-dependent
capabilities, bounded folder and owner Trash pages, common Trash lifecycle entries,
pending-ancestor exclusion and retained owner-only deletion-operation polling.
202 returns the original event UUID/Location. Polling distinguishes accepted,
processing, blocked, failed and receipt-backed logical completion; it never
asserts physical reclamation. Errors share the uppercase ApiError envelope across
authenticated and public-link endpoints, preserving public-link uniform 404s.
Read projections add no migration, mutation authority or lock-order change.

## Concurrency model

[ADR-0007](decisions/0007-concurrency-locking.md) is the exact lock-order authority. Transaction advisory locks use Hibernate's enlisted connection and stable keys. Collisions conservatively serialize work. Only immutable identity may precede coordination.

- Folder structural/grant mutations use exclusive owner hierarchy coordination. File operations use shared coordination plus optimistic versions or fresh eligible-row locks.
- Restore uses hierarchy → eligible file row → destination namespace lock.
- Multipart uses hierarchy → fresh file → allocation → session → decision/progress.
- Purge uses hierarchy → resource rows → allocation/work/run/progress → final job fence. Metadata pages use exclusive coordination and UUID row order. Job-only transactions acquire no later resource locks.
- Authentication locks its refresh family before mutable family/token reads.
- Charge changes lock the owner quota row from the file trigger: after a new reservation file insert and before its new allocation/session, or after existing-file lifecycle coordination. Counter repair uses exclusive hierarchy, then quota row, then a fresh aggregate. No lock spans Garage I/O.

No transaction or hierarchy lock spans Garage, signing or broker I/O. Status/version CAS and live token/clock fences reject stale transitions. Pending-purge ancestry blocks restore root fallback and subtree escape. Lock-scope performance remains unmeasured.

Cross-owner filename search follows Shared with me's single-statement snapshot
model, acquiring no multi-owner hierarchy locks or resource row locks. Mutations
retain all existing coordination and lock order. An overlapping transaction may
commit after the search snapshot; result capabilities are informational and every
later operation must freshly authorize. Search does not promise linearizable
revocation or a snapshot across offset-page requests.

## Direct multipart and retained historical uploads

POST `/api/v1/upload-sessions` is the sole new-upload admission. It atomically reserves UPLOADING metadata, a DIRECT_MULTIPART allocation and an idempotent session with immutable owner/initiator, destination, size/type, geometry and expiry. Retired POST `/api/v1/files` returns 405 to authenticated callers; anonymous callers still require authentication. No synchronous PUT fallback exists.

Only a newly committed initiation permission permits CreateMultipartUpload. OPEN requires the retained exact upload ID. Per-part length/SHA-256 is immutable; only UploadPart is presigned, for bounded lifetimes/batches. Resume uses bounded authoritative ListParts pages. Fresh actor/ancestry authorization gates admissions and responses. Revocation cannot recall existing URLs.

Completion freezes one authoritative manifest and dispatch identity, commits strict positive object evidence in a separate immutable receipt, then performs fresh READY CAS. Recovery uses that receipt or strict checksum/metadata evidence for the original dispatcher. Unknown Create/Complete/Abort never grants redispatch. Cancel/expiry records mutually exclusive abandonment and releases eligible names via FAILED; abort acknowledgement never proves physical reclamation.

Historical SERVER_PUT allocations, receipts, migrations and targets remain. Read-only inventory, verified never-admitted expiry, receipt-only READY recovery, restricted receipt-backed FAILED cleanup and historical SUCCEEDED purge recovery remain explicit internal operations. Missing receipts/unknown bindings are not backfilled from current settings or negative observations. No new SERVER_PUT allocation, admission, PUT or success-receipt writer remains in production.

The size policy remains positive files up to five GiB, fixed eight-MiB parts and bounded part count. Zero-byte uploads require a separate product decision. See [sessions](multipart-upload-sessions.md), [transfers](multipart-part-transfers.md), [completion](multipart-completion-recovery.md), [cancellation](multipart-cancellation-expiry.md) and [historical recovery](upload-success-recovery.md).

## Trash, purge and asynchronous work

Per-owner quota accounting uses READY file lengths as used and UPLOADING lengths
as reserved. Trash and pending purge remain charged through durable file metadata
removal. The authenticated [usage API and repair procedure](storage-quotas.md)
report limit/used/reserved/available plus separately inventoried uncertain
write-admitted allocations. Unknown physical reclamation never increases logical
capacity by itself. [ADR-0011](decisions/0011-storage-quota-accounting.md) records
the lock and backfill rules.

Only accessible active READY files are usable. Rename/move update metadata without object copies. Trash retains bytes and releases names; restore keeps identity and resolves collisions while preserving extensions. Purge requests record intent/outbox atomically, return 202 and perform no storage I/O. Pending folder intent makes its subtree immutable.

The publisher claims committed rows, publishes outside transactions and requires broker confirm without mandatory return before PUBLISHED. Uncertain outcomes replay the same event UUID. Typed consumers validate source identity, commit deduplicated jobs/notifications or sanitized quarantine, then ACK. Both use one three-attempt delivery policy; exhaustion stops unacknowledged work. Permission routing remains separate from purge.

Workers reserve bounded capacity before claims, renew live leases, check cancellation and record fenced outcomes. Useful CONTINUE checkpoints do not spend failure budget; failures/abandoned claims do. Byte deletion and metadata receipts are separate. Folder discovery seals membership before file work, then bounded bottom-up metadata pages retain child/root receipts and FINALIZED proof. Historical SUCCEEDED means byte success; missing metadata alone is not proof.

READY multipart deletion requires original completion evidence/binding. Unfinished folder members use logical-discard receipts and retain possible storage effects. Folder FINALIZED does not assert reclamation of discarded effects. Garage deletion relies on trusted writers and permanent key nonreuse; leases cannot fence external effects.

Publisher, purge consumer, permission consumer, worker and missing-job reconciler are independently disabled by default. Reconciliation inserts only aged-PUBLISHED, unquarantined matching intent/target/run work; it never resets jobs/proofs. See [ADR-0008](decisions/0008-transactional-outbox-rabbitmq.md), [file purge](file-purge.md), [folder purge](folder-purge-design.md), [M5](multipart-purge-compatibility.md) and [missing-job recovery](missing-job-reconciliation.md).

## Test evidence

Tests cover unit/controller contracts, real PostgreSQL constraints/rollback/races, embedded HTTP/OSIV, isolated RabbitMQ and disposable Garage behavior. Historical fixtures explicitly reconstruct old state without a production compatibility adapter. Removed-route tests check rejection without metadata/allocation/storage effects and direct-session reservation/idempotency. Process harnesses now use direct multipart HTTP setup with their independent execution gates.

[Consolidation verification](backend-consolidation.md#verification) records final results and gated skips. Historical [continuous purge](async-purge-verification.md) and [process recovery](async-process-recovery.md) records are evidence for their recorded revisions, not claims that gated runs executed now.

The MVP quota milestone's final 2026-10-21 clean backend run passed 1,331 tests
with zero failures/errors and 20 gated skips on isolated PostgreSQL schemas,
including live RabbitMQ, Garage multipart and the HTTP-to-Garage purge harness.
The skipped checks were process-kill recovery (1), the separate Garage capability
characterization (18), and retained-journal recovery (1). Focused quota,
delegated upload, lifecycle and purge races also passed. Test isolation was
extended to older fixed-schema suites without changing their shared data.

The [browser harness](browser-multipart-interoperability.md) provides exact-body/checksum, refresh serialization and proxy checks. **Live browser interoperability remains unverified** pending approved HTTPS, real-origin CORS, browser execution and bounded clocks. Java/Node checks do not establish browser behavior.

### Filename search verification

On 2026-10-22, the final focused selection (`SearchIntegrationTest`,
`ApiContractConcurrencyIntegrationTest`, `SharingIntegrationTest`,
`SharingConcurrencyIntegrationTest`) passed **134 tests, zero failures/errors/skips**.
The 39 search cases use a fresh `search_<UUID>` PostgreSQL schema and committed
per-actor fixtures. They cover owner/Viewer/Editor/stranger access, multiple owners,
direct/inherited/overlapping grants, private parents/siblings, literal `%`, `_`,
`!` and backslash, Unicode names, MIME declarations with charset, zero/nonzero size,
inclusive dates, invalid bounds, deterministic type/UUID tie breakers, empty/large
offset pages, non-READY/Trash/purge barriers, cycles/depth and sanitized lock timeout.
Latch-held transactions verify old committed search content/count/role/parent state
during revoke, downgrade, file/folder move, Trash, restore and purge, followed by
fresh post-commit visibility and ordinary operation reauthorization.

The final clean full suite passed **1,370 tests: 1,350 passed, 20 skipped, zero
failures/errors**, against PostgreSQL 17.11 and inspected Garage 2.3.0/RabbitMQ
4.3.6. It includes 12 live isolated RabbitMQ tests, 27 gated Garage multipart cases,
and the real HTTP-to-Garage asynchronous purge harness. Both harness events reported
`initialDueAligned=false`; the test-only scheduling adjustment was unnecessary.
The task-owned tunnel was closed after verification. Synthetic verification
schemas remain retained; the continuous harness cleaned only its own allocations
and private topology/schema. No shared migration checksum was repaired.

| Gated suite | Skipped | Separate prerequisite, not enabled |
|---|---:|---|
| `AsyncPurgeProcessRecoveryTest` | 1 | `ASYNC_PROCESS_TEST` and process-kill runbook |
| `GarageMultipartCapabilityTest` | 18 | `GARAGE_MULTIPART_TEST` and M0 characterization |
| `GarageMultipartJournalRecoveryTest` | 1 | Separately reviewed retained-run UUID |

These skipped scenarios are unverified by this milestone. `git diff --check`,
staged-file/diff review and local documentation link checks also passed before
commit. The real table-lock test exposed JPA `PessimisticLockException` on native
search reads; the common timeout advice now includes JPA pessimistic/lock timeout
exceptions so that path returns sanitized 503 instead of 500.

Reproduce from `backend/` with the ignored environment loaded in the Maven shell,
the task-owned AMQP tunnel and existing private-vhost isolation from
[the continuous runbook](async-purge-verification.md). Set
`RABBITMQ_TEST_VHOST=vaultdrive_outbox_test`, `ASYNC_E2E_TEST=true`,
`ASYNC_E2E_ALIGN_INITIAL_DUE=true`, `GARAGE_MULTIPART_TRANSFER_TEST=true`,
`GARAGE_MULTIPART_COMPLETION_TEST=true`, `GARAGE_MULTIPART_CANCELLATION_TEST=true`,
`GARAGE_MULTIPART_PURGE_TEST=true`, and `GARAGE_MULTIPART_VERSION=2.3.0`, then run:

```powershell
.\mvnw.cmd -o clean test '-Dspring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=filename_search_final_verification' '-Dspring.flyway.schemas=filename_search_final_verification' '-Dspring.flyway.default-schema=filename_search_final_verification'
```

Default contexts use that isolated test schema; suites with their own fresh schema
retain their isolation. Normal publisher/consumer/worker/reconciler defaults stay
off. No development metadata, shared fixture repair, VM configuration or applied
migration is changed. Search adds no external I/O, mutation or lock-order change.
V40 installs `pg_trgm` only in the tested database; production deployment still
needs the documented extension prerequisite. Capacity, latency, browser behavior,
cross-request snapshots and content/full-text/semantic search remain unverified
or outside scope.

## Remaining work

Unknown multipart reclamation, broad historical repair, retention/erasure, zero-byte support, general rate limiting, JWT key evolution, quota notifications, dependency readiness probes, backups/disaster recovery, clients and deployment remain separate. Performance, multi-node failure and capacity targets are unmeasured. No shared checksum repair, old allocation sweep or VM change is part of consolidation. See the [roadmap](development-roadmap.md) and [risk register](backend-consolidation.md#deferred-decisions-and-risks).
